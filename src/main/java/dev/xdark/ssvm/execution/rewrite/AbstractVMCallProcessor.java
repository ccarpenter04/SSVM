package dev.xdark.ssvm.execution.rewrite;

import dev.xdark.ssvm.VirtualMachine;
import dev.xdark.ssvm.asm.VMCallInsnNode;
import dev.xdark.ssvm.execution.ExecutionContext;
import dev.xdark.ssvm.execution.InstructionProcessor;
import dev.xdark.ssvm.execution.Locals;
import dev.xdark.ssvm.execution.Result;
import dev.xdark.ssvm.execution.Stack;
import dev.xdark.ssvm.mirror.JavaMethod;
import dev.xdark.ssvm.thread.ThreadStorage;
import dev.xdark.ssvm.value.Value;

/**
 * Base class for VM calls.
 *
 * @author xDark
 */
abstract class AbstractVMCallProcessor implements InstructionProcessor<VMCallInsnNode> {

	@Override
	public final Result execute(VMCallInsnNode insn, ExecutionContext ctx) {
		JavaMethod method = insn.getResolved();
		if (method == null || alwaysResolve()) {
			// The one who override the method
			// must replace resolved field, if needed
			method = resolveMethod(insn, ctx);
		}
		VirtualMachine vm = ctx.getVM();
		Stack callerStack = ctx.getStack();
		ThreadStorage storage = vm.getThreadStorage();
		int maxStack = method.getNode().maxStack;
		int maxLocals = method.getMaxLocals();
		int maxArgs = method.getMaxArgs();
		Stack stack = storage.newStack(maxStack);
		Locals locals = storage.newLocals(maxLocals);
		callerStack.sinkInto(locals, maxArgs);
		Value result = vm.getHelper().invokeDirect(method, stack, locals).getResult();
		if (!result.isVoid()) {
			callerStack.pushGeneric(result);
		}
		return Result.CONTINUE;
	}

	protected abstract JavaMethod resolveMethod(VMCallInsnNode insn, ExecutionContext ctx);

	protected abstract boolean alwaysResolve();
}
