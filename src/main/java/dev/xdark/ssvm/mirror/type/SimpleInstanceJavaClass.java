package dev.xdark.ssvm.mirror.type;

import dev.xdark.ssvm.VirtualMachine;
import dev.xdark.ssvm.asm.Modifier;
import dev.xdark.ssvm.execution.Locals;
import dev.xdark.ssvm.execution.PanicException;
import dev.xdark.ssvm.execution.VMException;
import dev.xdark.ssvm.memory.management.MemoryManager;
import dev.xdark.ssvm.mirror.MirrorFactory;
import dev.xdark.ssvm.mirror.member.JavaField;
import dev.xdark.ssvm.mirror.member.JavaMethod;
import dev.xdark.ssvm.mirror.member.MemberIdentifier;
import dev.xdark.ssvm.mirror.member.area.ClassArea;
import dev.xdark.ssvm.mirror.member.area.SimpleClassArea;
import dev.xdark.ssvm.symbol.VMSymbols;
import dev.xdark.ssvm.tlc.ThreadLocalStorage;
import dev.xdark.ssvm.util.Assertions;
import dev.xdark.ssvm.util.VMHelper;
import dev.xdark.ssvm.value.InstanceValue;
import dev.xdark.ssvm.value.JavaValue;
import dev.xdark.ssvm.value.ObjectValue;
import me.coley.cafedude.InvalidClassException;
import me.coley.cafedude.classfile.ClassFile;
import me.coley.cafedude.io.ClassFileReader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SimpleInstanceJavaClass implements InstanceJavaClass {

	private static final String POLYMORPHIC_DESC = "([Ljava/lang/Object;)Ljava/lang/Object;";
	private static final Predicate<JavaField> NON_HIDDEN_FIELD = nonHidden(JavaField::getModifiers);
	private static final Predicate<JavaMethod> NON_HIDDEN_METHOD = nonHidden(JavaMethod::getModifiers);

	private final VirtualMachine vm;
	private final ObjectValue classLoader;

	private final Lock initializationLock;
	private final Condition signal;

	private ClassReader classReader;
	private ClassNode node;
	private ClassFile rawClassFile;

	private InstanceValue oop;

	private InstanceJavaClass superClass;
	private InstanceJavaClass[] interfaces;
	private volatile ArrayJavaClass arrayClass;
	private volatile State state = State.PENDING;
	private ClassArea<JavaMethod> methodArea;
	private ClassArea<JavaField> virtualFieldArea;
	private ClassArea<JavaField> staticFieldArea;
	private long occupiedInstanceSpace;
	private long occupiedStaticSpace;

	private String normalName;
	private String descriptor;

	// Reflection cache
	private List<JavaMethod> declaredConstructors;
	private List<JavaMethod> publicConstructors;
	private List<JavaMethod> declaredMethods;
	private List<JavaMethod> publicMethods;
	private List<JavaField> declaredFields;
	private List<JavaField> publicFields;
	private Boolean allocationStatus;
	private Type type;

	/**
	 * This constructor must be invoked ONLY
	 * by the VM.
	 *
	 * @param vm          VM.
	 * @param classLoader Loader of the class.
	 * @param classReader Source of the class.
	 * @param node        ASM class data.
	 * @param oop         Clas oop.
	 */
	public SimpleInstanceJavaClass(VirtualMachine vm, ObjectValue classLoader, ClassReader classReader, ClassNode node, InstanceValue oop) {
		this.vm = vm;
		this.classLoader = classLoader;
		this.classReader = classReader;
		this.node = node;
		this.oop = oop;
		ReentrantLock lock = new ReentrantLock();
		initializationLock = lock;
		signal = lock.newCondition();
	}

	/**
	 * This constructor must be invoked ONLY
	 * by the VM.
	 *
	 * @param vm          VM instance.
	 * @param classLoader Loader of the class.
	 * @param classReader Source of the class.
	 * @param node        ASM class data.
	 */
	public SimpleInstanceJavaClass(VirtualMachine vm, ObjectValue classLoader, ClassReader classReader, ClassNode node) {
		this(vm, classLoader, classReader, node, null);
	}

	@Override
	public String getName() {
		String normalName = this.normalName;
		if (normalName == null) {
			return this.normalName = node.name.replace('/', '.');
		}
		return normalName;
	}

	@Override
	public String getInternalName() {
		return node.name;
	}

	@Override
	public String getDescriptor() {
		String descriptor = this.descriptor;
		if (descriptor == null) {
			return this.descriptor = 'L' + node.name + ';';
		}
		return descriptor;
	}

	@Override
	public int getModifiers() {
		return node.access;
	}

	@Override
	public ObjectValue getClassLoader() {
		return classLoader;
	}

	@Override
	public InstanceValue getOop() {
		return oop;
	}

	@Override
	public void initialize() {
		Lock lock = initializationLock;
		lock.lock();
		State state = this.state;
		if (state == State.COMPLETE || state == State.IN_PROGRESS) {
			lock.unlock();
			return;
		}
		if (state == State.FAILED) {
			lock.unlock();
			VirtualMachine vm = this.vm;
			vm.getHelper().throwException(vm.getSymbols().java_lang_NoClassDefFoundError(), getInternalName());
		}
		this.state = State.IN_PROGRESS;
		VirtualMachine vm = this.vm;
		VMHelper helper = vm.getHelper();
		// Initialize all hierarchy
		InstanceJavaClass superClass = this.superClass;
		if (superClass != null) {
			superClass.initialize();
		}
		// note: interfaces are *not* initialized here
		helper.initializeStaticFields(this);
		helper.setupHiddenFrames(this);
		JavaMethod clinit = getMethod("<clinit>", "()V");
		try {
			if (clinit != null) {
				Locals locals = vm.getThreadStorage().newLocals(clinit);
				helper.invoke(clinit, locals);
			}
			this.state = State.COMPLETE;
		} catch (VMException ex) {
			markFailedInitialization(ex);
		} finally {
			signal.signalAll();
			lock.unlock();
		}
	}

	@Override
	public void link() {
		Lock lock = initializationLock;
		lock.lock();
		state = State.IN_PROGRESS;
		try {
			try {
				loadSuperClass();
				// loadInterfaces();
			} catch (VMException ex) {
				markFailedInitialization(ex);
			}
			VMHelper helper = vm.getHelper();
			// Create method and field area
			List<JavaField> virtualFields = new ArrayList<>();
			InstanceJavaClass jc = superClass;
			JavaField lastField = null;
			while (jc != null) {
				ClassArea<JavaField> area = jc.virtualFieldArea();
				// May be java/lang/Class calling to java/lang/Object
				if (area == null) {
					Assertions.check(jc == vm.getSymbols().java_lang_Object(), "null area is only allowed for java/lang/Object");
				} else {
					JavaField field = area.stream()
						.filter(x -> (x.getModifiers() & Opcodes.ACC_STATIC) == 0)
						.max(Comparator.comparingLong(JavaField::getOffset))
						.orElse(null);
					if (field != null && (lastField == null || field.getOffset() > lastField.getOffset())) {
						lastField = field;
					}
				}
				jc = jc.getSuperClass();
			}
			VirtualMachine vm = this.vm;
			MemoryManager mgr = vm.getMemoryManager();
			long offset;
			if (lastField != null) {
				offset = lastField.getOffset();
				offset += helper.getDescriptorSize(lastField.getDesc());
			} else {
				offset = mgr.valueBaseOffset(this);
			}

			List<FieldNode> fields = node.fields;
			MirrorFactory mf = vm.getMirrorFactory();
			int slot = 0;
			for (int i = 0, j = fields.size(); i < j; i++) {
				FieldNode fieldNode = fields.get(i);
				if ((fieldNode.access & Opcodes.ACC_STATIC) == 0) {
					JavaField field = mf.newField(this, fieldNode, slot++, offset);
					offset += helper.getDescriptorSize(fieldNode.desc);
					virtualFields.add(field);
				}
			}
			virtualFieldArea = new SimpleClassArea<>(virtualFields);
			occupiedInstanceSpace = offset - mgr.valueBaseOffset(this);
			int slotOffset = slot;
			// Static fields are stored right after java/lang/Class virtual fields
			// At this point of linkage java/lang/Class must already set its virtual
			// fields as we are doing it before (see above)
			JavaField maxVirtualField = vm.getSymbols().java_lang_Class().virtualFieldArea()
				.stream()
				.max(Comparator.comparingLong(JavaField::getOffset))
				.orElseThrow(() -> new PanicException("No fields in java/lang/Class"));
			offset = maxVirtualField.getOffset() + helper.getDescriptorSize(maxVirtualField.getDesc());
			long baseStaticOffset = offset;
			List<JavaField> staticFields = new ArrayList<>(fields.size() - slot);
			for (int i = 0, j = fields.size(); i < j; i++) {
				FieldNode fieldNode = fields.get(i);
				if ((fieldNode.access & Opcodes.ACC_STATIC) != 0) {
					JavaField field = mf.newField(this, fieldNode, slot++, offset);
					offset += helper.getDescriptorSize(fieldNode.desc);
					staticFields.add(field);
				}
			}
			staticFieldArea = new SimpleClassArea<>(staticFields, slotOffset);
			occupiedStaticSpace = offset - baseStaticOffset;
			// Set methods
			List<MethodNode> methods = node.methods;
			List<JavaMethod> allMethods = new ArrayList<>(methods.size());
			for (int i = 0, j = methods.size(); i < j; i++) {
				allMethods.add(mf.newMethod(this, methods.get(i), i));
			}
			methodArea = new SimpleClassArea<>(allMethods);
			state = State.PENDING;
		} finally {
			signal.signalAll();
			lock.unlock();
		}
	}

	@Override
	public boolean isAssignableFrom(JavaClass other) {
		if (other == null) {
			VirtualMachine vm = this.vm;
			vm.getHelper().throwException(vm.getSymbols().java_lang_NullPointerException());
		}
		if (other == this) {
			return true;
		}
		if (other.isPrimitive()) {
			return false;
		}
		VMSymbols symbols = vm.getSymbols();
		if (other.isArray()) {
			if (isInterface()) {
				return this == symbols.java_io_Serializable() || this == symbols.java_lang_Cloneable();
			} else {
				return this == symbols.java_lang_Object();
			}
		}
		if (this == symbols.java_lang_Object()) {
			return true;
		}
		if (other.isInterface()) {
			if (isInterface()) {
				Deque<InstanceJavaClass> toCheck = new ArrayDeque<>(Arrays.asList(other.getInterfaces()));
				JavaClass popped;
				while ((popped = toCheck.poll()) != null) {
					if (popped == this) {
						return true;
					}
					toCheck.addAll(Arrays.asList(popped.getInterfaces()));
				}
			}
		} else {
			Deque<JavaClass> toCheck = new ArrayDeque<>();
			JavaClass superClass = other.getSuperClass();
			if (superClass != null) {
				toCheck.add(superClass);
			}
			if (isInterface()) {
				toCheck.addAll(Arrays.asList(other.getInterfaces()));
				JavaClass popped;
				while ((popped = toCheck.poll()) != null) {
					if (popped == this) {
						return true;
					}
					superClass = popped.getSuperClass();
					if (superClass != null) {
						toCheck.add(superClass);
					}
					toCheck.addAll(Arrays.asList(popped.getInterfaces()));
				}
			} else {
				JavaClass popped;
				while ((popped = toCheck.poll()) != null) {
					if (popped == this) {
						return true;
					}
					superClass = popped.getSuperClass();
					if (superClass != null) {
						toCheck.add(superClass);
					}
				}
			}
		}
		return false;
	}

	@Override
	public boolean isPrimitive() {
		return false;
	}

	@Override
	public boolean isArray() {
		return false;
	}

	@Override
	public boolean isInterface() {
		return (node.access & Opcodes.ACC_INTERFACE) != 0;
	}

	@Override
	public JavaClass getComponentType() {
		return null;
	}

	@Override
	public InstanceJavaClass getSuperClass() {
		return superClass;
	}

	@Override
	public InstanceJavaClass[] getInterfaces() {
		loadInterfaces();
		return interfaces;
	}

	@Override
	public ArrayJavaClass newArrayClass() {
		ArrayJavaClass arrayClass = this.arrayClass;
		if (arrayClass == null) {
			synchronized (this) {
				arrayClass = this.arrayClass;
				if (arrayClass == null) {
					VirtualMachine vm = this.vm;
					arrayClass = new ArrayJavaClass(vm, '[' + getDescriptor(), 1, this);
					vm.getHelper().setComponentType(arrayClass, this);
					this.arrayClass = arrayClass;
				}
			}
		}
		return arrayClass;
	}

	@Override
	public ArrayJavaClass getArrayClass() {
		return arrayClass;
	}

	@Override
	public VirtualMachine getVM() {
		return vm;
	}

	@Override
	public void setOop(JavaValue<InstanceJavaClass> oop) {
		this.oop = oop;
	}

	@Override
	public JavaField getField(String name, String desc) {
		MemberIdentifier identifier = MemberIdentifier.of(name, desc);
		JavaField field = staticFieldArea.get(identifier);
		if (field == null) {
			field = virtualFieldArea.get(identifier);
		}
		return field;
	}

	@Override
	public JavaMethod getMethod(String name, String desc) {
		ClassArea<JavaMethod> methodArea = this.methodArea;
		JavaMethod method = methodArea.get(name, desc);
		if (method == null) {
			// Polymorphic?
			method = methodArea.get(name, POLYMORPHIC_DESC);
			if (method != null) {
				if (method.isPolymorphic()) {
					method = vm.getMirrorFactory().newPolymorphicMethod(method, desc);
				} else {
					method = null;
				}
			}
		}
		return method;
	}

	@Override
	public ClassNode getNode() {
		return node;
	}

	@Override
	public ClassReader getClassReader() {
		return classReader;
	}

	@Override
	public JavaMethod getMethodBySlot(int slot) {
		return methodArea.get(slot);
	}

	@Override
	public JavaField getFieldBySlot(int slot) {
		JavaField field = virtualFieldArea.get(slot);
		if (field == null) {
			field = staticFieldArea.get(slot);
		}
		return field;
	}

	@Override
	public ClassArea<JavaMethod> methodArea() {
		return methodArea;
	}

	@Override
	public ClassArea<JavaField> virtualFieldArea() {
		return virtualFieldArea;
	}

	@Override
	public ClassArea<JavaField> staticFieldArea() {
		return staticFieldArea;
	}

	@Override
	public long getOccupiedInstanceSpace() {
		return occupiedInstanceSpace;
	}

	@Override
	public long getOccupiedStaticSpace() {
		return occupiedStaticSpace;
	}

	@Override
	public List<JavaMethod> getDeclaredMethods(boolean publicOnly) {
		if (publicOnly) {
			List<JavaMethod> publicMethods = this.publicMethods;
			if (publicMethods == null) {
				return this.publicMethods = getDeclaredMethods0(true, false);
			}
			return publicMethods;
		}
		List<JavaMethod> declaredMethods = this.declaredMethods;
		if (declaredMethods == null) {
			return this.declaredMethods = getDeclaredMethods0(false, false);
		}
		return declaredMethods;
	}

	@Override
	public List<JavaMethod> getDeclaredConstructors(boolean publicOnly) {
		if (publicOnly) {
			List<JavaMethod> publicConstructors = this.publicConstructors;
			if (publicConstructors == null) {
				return this.publicConstructors = getDeclaredMethods0(true, true);
			}
			return publicConstructors;
		}
		List<JavaMethod> declaredConstructors = this.declaredConstructors;
		if (declaredConstructors == null) {
			return this.declaredConstructors = getDeclaredMethods0(false, true);
		}
		return declaredConstructors;
	}

	@Override
	public List<JavaField> getDeclaredFields(boolean publicOnly) {
		if (publicOnly) {
			List<JavaField> publicFields = this.publicFields;
			if (publicFields == null) {
				return this.publicFields = getDeclaredFields0(true);
			}
			return publicFields;
		}
		List<JavaField> declaredFields = this.declaredFields;
		if (declaredFields == null) {
			return this.declaredFields = getDeclaredFields0(false);
		}
		return declaredFields;
	}

	@Override
	public ClassFile getRawClassFile() {
		ClassFile rawClassFile = this.rawClassFile;
		if (rawClassFile == null) {
			try {
				ClassFileReader classFileReader = ThreadLocalStorage.get().getClassFileReader();
				return this.rawClassFile = classFileReader.read(classReader.b);
			} catch (InvalidClassException ex) {
				// Should not happen.
				// unless??
				throw new RuntimeException("Cafedude returned invalid class file", ex);
			}
		}
		return rawClassFile;
	}

	@Override
	public boolean shouldBeInitialized() {
		Lock lock = initializationLock;
		lock.lock();
		boolean isPending = state == State.PENDING;
		lock.unlock();
		return isPending;
	}

	@Override
	public InstanceJavaClass getSuperclassWithoutResolving() {
		String superName = node.superName;
		if (superName == null) {
			return null;
		}
		InstanceJavaClass superClass = this.superClass;
		if (superClass == null) {
			VirtualMachine vm = this.vm;
			return this.superClass = (InstanceJavaClass) vm.findClass(classLoader, superName, false);
		}
		return superClass;
	}

	@Override
	public synchronized void redefine(ClassReader reader, ClassNode node) {
		ClassNode current = this.node;
		verifyMembers(current.methods, node.methods, it -> it.name, it -> it.desc, it -> it.access);
		verifyMembers(current.fields, node.fields, it -> it.name, it -> it.desc, it -> it.access);
		classReader = reader;
		this.node = node;
		rawClassFile = null;
	}

	@Override
	public boolean canAllocateInstance() {
		// Its okay if this gets computed multiple times
		Boolean allocationStatus = this.allocationStatus;
		if (allocationStatus == null) {
			return this.allocationStatus = checkAllocationStatus();
		}
		return allocationStatus;
	}

	@Override
	public Type getType() {
		Type type = this.type;
		if (type == null) {
			type = Type.getType(getDescriptor());
			this.type = type;
		}
		return type;
	}

	@Override
	public int getSort() {
		return Type.OBJECT;
	}

	@Override
	public State getState() {
		return state;
	}

	@Override
	public String toString() {
		return getName();
	}

	private boolean checkAllocationStatus() {
		int acc = getModifiers();
		if ((acc & Opcodes.ACC_ABSTRACT) == 0 && (acc & Opcodes.ACC_INTERFACE) == 0) {
			return this != vm.getSymbols().java_lang_Class();
		}
		return false;
	}

	private List<JavaMethod> getDeclaredMethods0(boolean publicOnly, boolean constructors) {
		return methodArea.stream()
			.filter(x -> {
				String name = x.getName();
				if ("<clinit>".equals(name)) {
					return false;
				}
				return constructors == "<init>".equals(name);
			})
			.filter(x -> !publicOnly || (x.getModifiers() & Opcodes.ACC_PUBLIC) != 0)
			.filter(NON_HIDDEN_METHOD)
			.collect(Collectors.toList());
	}

	private List<JavaField> getDeclaredFields0(boolean publicOnly) {
		return Stream.concat(virtualFieldArea.stream(), staticFieldArea.stream())
			.filter(x -> !publicOnly || (x.getModifiers() & Opcodes.ACC_PUBLIC) != 0)
			.filter(NON_HIDDEN_FIELD)
			.collect(Collectors.toList());
	}

	private void loadSuperClass() {
		InstanceJavaClass superClass = this.superClass;
		if (superClass == null) {
			VirtualMachine vm = this.vm;
			String superName = node.superName;
			if (superName != null) {
				// Load parent class.
				superClass = (InstanceJavaClass) vm.findClass(classLoader, superName, false);
				this.superClass = superClass;
			}
		}
	}

	private void loadInterfaces() {
		InstanceJavaClass[] $interfaces = this.interfaces;
		if ($interfaces == null) {
			List<String> _interfaces = node.interfaces;
			$interfaces = new InstanceJavaClass[_interfaces.size()];
			VirtualMachine vm = this.vm;
			ObjectValue classLoader = this.classLoader;
			for (int i = 0, j = _interfaces.size(); i < j; i++) {
				$interfaces[i] = (SimpleInstanceJavaClass) vm.findClass(classLoader, _interfaces.get(i), false);
			}
			this.interfaces = $interfaces;
		}
	}

	private void markFailedInitialization(VMException ex) {
		state = State.FAILED;
		InstanceValue oop = ex.getOop();
		VirtualMachine vm = this.vm;
		VMSymbols symbols = vm.getSymbols();
		if (!symbols.java_lang_Error().isAssignableFrom(oop.getJavaClass())) {
			InstanceJavaClass jc = symbols.java_lang_ExceptionInInitializerError();
			jc.initialize();
			InstanceValue cause = oop;
			oop = vm.getMemoryManager().newInstance(jc);
			// Can't use newException here
			JavaMethod init = vm.getPublicLinkResolver().resolveSpecialMethod(jc, "<init>", "(Ljava/lang/Throwable;)V");
			Locals locals = vm.getThreadStorage().newLocals(init);
			locals.setReference(0, oop);
			locals.setReference(1, cause);
			vm.getHelper().invoke(init, locals);
			throw new VMException(oop);
		}
		throw ex;
	}

	private static <T> Predicate<T> nonHidden(ToIntFunction<T> function) {
		return x -> !Modifier.isHiddenMember(function.applyAsInt(x));
	}

	private static <T> void verifyMembers(List<T> current, List<T> redefined, Function<T, String> name, Function<T, String> desc, ToIntFunction<T> access) {
		if (current.size() != redefined.size()) {
			throw new IllegalStateException("Size mismatch");
		}
		for (int i = 0; i < current.size(); i++) {
			T t1 = current.get(i);
			T t2 = redefined.get(i);
			if (!name.apply(t1).equals(name.apply(t2))) {
				throw new IllegalStateException("Member name changed");
			}
			if (!desc.apply(t1).equals(desc.apply(t2))) {
				throw new IllegalStateException("Member descriptor changed");
			}
			if ((access.applyAsInt(t1) & Opcodes.ACC_STATIC) != (access.applyAsInt(t2) & Opcodes.ACC_STATIC)) {
				throw new IllegalStateException("Static access changed");
			}
		}
	}
}