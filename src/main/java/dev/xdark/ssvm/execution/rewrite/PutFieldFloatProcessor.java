package dev.xdark.ssvm.execution.rewrite;

import dev.xdark.ssvm.asm.VMFieldInsnNode;
import dev.xdark.ssvm.execution.ExecutionContext;
import dev.xdark.ssvm.execution.InstructionProcessor;
import dev.xdark.ssvm.execution.Result;
import dev.xdark.ssvm.execution.Stack;
import dev.xdark.ssvm.mirror.type.InstanceJavaClass;
import dev.xdark.ssvm.mirror.member.JavaField;
import dev.xdark.ssvm.value.InstanceValue;
import org.objectweb.asm.tree.FieldInsnNode;

/**
 * Fast path processor for PUTFIELD.
 *
 * @author xDark
 */
public final class PutFieldFloatProcessor implements InstructionProcessor<VMFieldInsnNode> {

	@Override
	public Result execute(VMFieldInsnNode insn, ExecutionContext<?> ctx) {
		Stack stack = ctx.getStack();
		float value = stack.popFloat();
		InstanceValue instance = ctx.getHelper().checkNotNull(stack.popReference());
		JavaField field = insn.getResolved();
		if (field == null) {
			InstanceJavaClass klass = instance.getJavaClass();
			FieldInsnNode delegate = insn.getDelegate();
			field = ctx.getLinkResolver().resolveVirtualField(klass, delegate.name, "F");
		}
		instance.getMemory().getData().writeInt(field.getOffset(), Float.floatToRawIntBits(value));
		return Result.CONTINUE;
	}
}
