package dev.xdark.ssvm.natives;

import dev.xdark.ssvm.NativeJava;
import dev.xdark.ssvm.VirtualMachine;
import dev.xdark.ssvm.classloading.ClassLoaderData;
import dev.xdark.ssvm.execution.Result;
import dev.xdark.ssvm.mirror.InstanceJavaClass;
import dev.xdark.ssvm.value.InstanceValue;
import dev.xdark.ssvm.value.JavaValue;
import dev.xdark.ssvm.value.NullValue;
import dev.xdark.ssvm.value.Value;
import lombok.experimental.UtilityClass;
import lombok.val;

/**
 * Initializes java/lang/reflect/Proxy.
 *
 * @author xDark
 */
@UtilityClass
public class ProxyNatives {

	private final String CLASS_LOADER_OOP = NativeJava.CLASS_LOADER_OOP;

	/**
	 * Initializes java/lang/reflect/Proxy.
	 *
	 * @param vm
	 * 		VM instance.
	 */
	public void init(VirtualMachine vm) {
		val vmi = vm.getInterface();
		val symbols = vm.getSymbols();
		val jc = symbols.java_lang_reflect_Proxy;
		vmi.setInvoker(jc, "defineClass0", "(Ljava/lang/ClassLoader;Ljava/lang/String;[BII)Ljava/lang/Class;", ctx -> {
			// Simply invoke defineClass in a loader.
			val locals = ctx.getLocals();
			val helper = vm.getHelper();
			val loader = locals.load(0);
			val name = locals.load(1);
			val bytes = helper.checkNotNullArray(locals.load(2));
			val off = locals.load(3);
			val len = locals.load(4);
			InstanceJavaClass result;
			if (loader.isNull()) {
				val parsed = vm.getClassDefiner().parseClass(helper.readUtf8(name), helper.toJavaBytes(bytes), off.asInt(), len.asInt(), "JVM_DefineClass");
				if (parsed == null) {
					helper.throwException(symbols.java_lang_InternalError, "Invalid bytecode");
				}
				result = helper.newInstanceClass(NullValue.INSTANCE, NullValue.INSTANCE, parsed.getClassReader(), parsed.getNode());
				vm.getBootClassLoaderData().forceLinkClass(result);
			} else {
				result = ((JavaValue<InstanceJavaClass>) helper.invokeVirtual("defineClass", "(Ljava/lang/String;[BII)Ljava/lang/Class;", new Value[0], new Value[]{
						loader,
						name,
						bytes,
						off,
						len
				}).getResult()).getValue();
				((JavaValue<ClassLoaderData>) ((InstanceValue) loader).getValue(CLASS_LOADER_OOP, "Ljava/lang/Object;")).getValue()
						.forceLinkClass(result);
			}
			ctx.setResult(result.getOop());
			return Result.ABORT;
		});
	}
}