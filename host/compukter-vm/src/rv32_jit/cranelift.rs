/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#![allow(
    dead_code,
    reason = "the Cranelift backend is introduced before the machine-owned JIT dispatcher"
)]

use super::backend::CodeBlob;
use super::block::JitBlockInput;
use crate::rv32im::{DecodedInstruction, ImmOp, Op, Rv32ArchitecturalState};
use cranelift_codegen::ir::{
    types, AbiParam, Function, InstBuilder, MemFlagsData, Signature, UserFuncName,
};
use cranelift_codegen::isa::TargetIsa;
use cranelift_codegen::settings::{self, Configurable};
use cranelift_codegen::Context;
use cranelift_control::ControlPlane;
use cranelift_frontend::{FunctionBuilder, FunctionBuilderContext};
use std::sync::Arc;

pub(crate) struct CraneliftBackend {
    isa: Arc<dyn TargetIsa>,
}

impl CraneliftBackend {
    pub(crate) fn new() -> Result<Self, String> {
        let mut shared_flags = settings::builder();
        shared_flags
            .set("opt_level", "speed")
            .map_err(|error| format!("RV32 Cranelift opt_level configuration failed: {error}"))?;
        let isa_builder = cranelift_native::builder()
            .map_err(|error| format!("RV32 Cranelift host target is unsupported: {error}"))?;
        Ok(Self {
            isa: isa_builder
                .finish(settings::Flags::new(shared_flags))
                .map_err(|error| format!("RV32 Cranelift host ISA setup failed: {error}"))?,
        })
    }

    pub(crate) fn compile(&self, input: &JitBlockInput) -> Result<CodeBlob, String> {
        if !input.is_supported() {
            return Err(format!(
                "RV32 Cranelift lowering does not support block at {:#010x}",
                input.start_pc()
            ));
        }
        let mut signature = Signature::new(self.isa.default_call_conv());
        signature
            .params
            .push(AbiParam::new(self.isa.pointer_type()));
        signature.returns.push(AbiParam::new(types::I32));
        let mut context = Context::for_function(Function::with_name_signature(
            UserFuncName::user(0, 0),
            signature,
        ));
        let mut function_builder_context = FunctionBuilderContext::new();
        {
            let mut builder =
                FunctionBuilder::new(&mut context.func, &mut function_builder_context);
            let entry = builder.create_block();
            builder.append_block_params_for_function_params(entry);
            builder.switch_to_block(entry);
            builder.seal_block(entry);
            let state = builder.block_params(entry)[0];
            for slot in input.slots() {
                lower_instruction(&mut builder, state, slot.instruction())?;
            }
            let next_pc = builder.ins().iconst(
                types::I32,
                input
                    .start_pc()
                    .wrapping_add((input.slots().len() as u32) * 4) as i64,
            );
            builder.ins().store(
                MemFlagsData::trusted(),
                next_pc,
                state,
                Rv32ArchitecturalState::PC_OFFSET as i32,
            );
            let count = builder.ins().iconst(types::I32, input.slots().len() as i64);
            builder.ins().return_(&[count]);
            builder.finalize(self.isa.frontend_config());
        }
        let mut control_plane = ControlPlane::default();
        let compiled = context
            .compile(self.isa.as_ref(), &mut control_plane)
            .map_err(|error| format!("RV32 Cranelift compilation failed: {error:?}"))?;
        if !compiled.buffer.relocs().is_empty() {
            return Err("RV32 Cranelift emitted unexpected external relocations".to_string());
        }
        Ok(CodeBlob::new(compiled.code_buffer().to_vec(), vec![]))
    }
}

fn lower_instruction(
    builder: &mut FunctionBuilder<'_>,
    state: cranelift_codegen::ir::Value,
    instruction: DecodedInstruction,
) -> Result<(), String> {
    match instruction {
        DecodedInstruction::Immediate {
            op: ImmOp::Add,
            rd,
            rs1,
            immediate,
        } => {
            let lhs = load_register(builder, state, rs1);
            let value = builder.ins().iadd_imm_s(lhs, i64::from(immediate));
            store_register(builder, state, rd, value);
            Ok(())
        }
        DecodedInstruction::Register {
            op: Op::Add,
            rd,
            rs1,
            rs2,
        } => {
            let lhs = load_register(builder, state, rs1);
            let rhs = load_register(builder, state, rs2);
            let value = builder.ins().iadd(lhs, rhs);
            store_register(builder, state, rd, value);
            Ok(())
        }
        _ => Err("RV32 Cranelift received a non-arithmetic JIT slot".to_string()),
    }
}

fn load_register(
    builder: &mut FunctionBuilder<'_>,
    state: cranelift_codegen::ir::Value,
    register: usize,
) -> cranelift_codegen::ir::Value {
    builder.ins().load(
        types::I32,
        MemFlagsData::trusted(),
        state,
        Rv32ArchitecturalState::register_offset(register) as i32,
    )
}

fn store_register(
    builder: &mut FunctionBuilder<'_>,
    state: cranelift_codegen::ir::Value,
    register: usize,
    value: cranelift_codegen::ir::Value,
) {
    if register != 0 {
        builder.ins().store(
            MemFlagsData::trusted(),
            value,
            state,
            Rv32ArchitecturalState::register_offset(register) as i32,
        );
    }
}

#[cfg(test)]
mod tests {
    use super::CraneliftBackend;
    use crate::rv32_jit::arena::ExecutableCodeArena;
    use crate::rv32_jit::block::JitBlockInput;
    use crate::rv32im::{
        decode_product_word,
        encoding::{add, addi},
        Rv32ArchitecturalState, Rv32ResolvedInstruction,
    };

    #[test]
    fn compiled_arithmetic_block_updates_canonical_rv32_state() {
        let words = [addi(7, 1, 5), add(8, 7, 1)];
        let input = JitBlockInput::from_resolved(
            0x1000,
            words
                .into_iter()
                .map(|word| Rv32ResolvedInstruction::Valid {
                    word,
                    instruction: decode_product_word(word).unwrap(),
                })
                .collect(),
        )
        .unwrap();
        let blob = CraneliftBackend::new().unwrap().compile(&input).unwrap();
        let mut arena = ExecutableCodeArena::new(4096).unwrap();
        let id = arena.stage(blob).unwrap();
        arena.seal_batch().unwrap();
        let entry: unsafe extern "C" fn(*mut u8) -> u32 =
            unsafe { std::mem::transmute(arena.entry_address(id).unwrap()) };
        let mut state = Rv32ArchitecturalState::new(0x1000);
        state.set_register(1, 10);

        assert_eq!(
            unsafe { entry((&mut state as *mut Rv32ArchitecturalState).cast()) },
            2
        );
        assert_eq!(state.pc(), 0x1008);
        assert_eq!(state.register(7), 15);
        assert_eq!(state.register(8), 25);
        assert_eq!(state.register(0), 0);
    }
}
