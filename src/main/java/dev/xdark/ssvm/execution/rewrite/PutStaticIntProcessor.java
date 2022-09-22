package dev.xdark.ssvm.execution.rewrite;

import dev.xdark.ssvm.asm.VMFieldInsnNode;
import dev.xdark.ssvm.execution.ExecutionContext;
import dev.xdark.ssvm.execution.InstructionProcessor;
import dev.xdark.ssvm.execution.Result;
import dev.xdark.ssvm.memory.management.MemoryManager;
import dev.xdark.ssvm.mirror.type.InstanceJavaClass;
import dev.xdark.ssvm.mirror.member.JavaField;

/**
 * Fast path processor for PUTSTATIC.
 *
 * @author xDark
 */
public final class PutStaticIntProcessor implements InstructionProcessor<VMFieldInsnNode> {

	@Override
	public Result execute(VMFieldInsnNode insn, ExecutionContext<?> ctx) {
		JavaField field = insn.getResolved();
		InstanceJavaClass klass = field.getOwner();
		klass.getOop().getData().writeInt(field.getOffset(), ctx.getStack().popInt());
		return Result.CONTINUE;
	}
}
