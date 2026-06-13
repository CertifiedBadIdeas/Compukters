use k16_vm::k16::{
    K16Cpu, K16Signal, K16_CSR_INTERRUPT_ENABLE, K16_CSR_INTERRUPT_MASK, K16_CSR_INTERRUPT_PENDING,
    K16_CSR_TRAP_ARG0, K16_CSR_TRAP_ARG1, K16_CSR_TRAP_ARG2, K16_CSR_TRAP_CAUSE,
    K16_CSR_TRAP_FRAME_INDEX, K16_CSR_TRAP_FRAME_REGISTER, K16_CSR_TRAP_INTERRUPT_ENABLE,
    K16_CSR_TRAP_PC, K16_CSR_TRAP_RESUME_PC, K16_CSR_TRAP_STACK_POINTER, K16_CSR_TRAP_VALUE,
    K16_CSR_TRAP_VECTOR, K16_INTERRUPT_SOURCE_TIMER0, K16_STACK_POINTER_REGISTER,
    K16_TRAP_CAUSE_EXPLICIT_TRAP, K16_TRAP_CAUSE_ILLEGAL_INSTRUCTION,
    K16_TRAP_CAUSE_TIMER0_INTERRUPT,
};
use k16_vm::low_bus::{MachineBus, MmioDevice};
use k16_vm::low_machine::MemoryFault;

#[test]
fn k16_fetches_decodes_and_executes_words_from_guest_memory() {
    let mut bus = MachineBus::new(64).unwrap();
    let mut program = vec![const4(1, 2), const4(2, 5)];
    program.extend(add(3, 1, 2));
    program.push(halt());
    write_words(&mut bus, 0, &program);
    let mut cpu = K16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), K16Signal::Halt,);
    assert_eq!(cpu.register(3), 7);
    assert_eq!(cpu.pc(), 10);
}

#[test]
fn k16_yield_signal_is_resumable() {
    let mut bus = MachineBus::new(64).unwrap();
    bus.map_mmio(
        0x1000_0000,
        Box::new(YieldDevice {
            yield_requested: false,
        }),
    )
    .unwrap();
    let mut program = Vec::new();
    program.extend(const32(1, 0x1000_000c));
    program.push(const4(2, 1));
    program.push(store32(1, 2));
    program.push(halt());
    write_words(&mut bus, 0, &program);
    let mut cpu = K16Cpu::new(0);

    assert_eq!(
        cpu.run_until_signal(&mut bus, 16).unwrap(),
        K16Signal::Yield
    );
    assert_eq!(cpu.pc(), 10);
    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), K16Signal::Halt);
    assert_eq!(cpu.pc(), 12);
}

#[test]
fn k16_wait_signal_is_resumable_without_terminal_halt() {
    let mut bus = MachineBus::new(64).unwrap();
    let program = vec![wait(), const4(1, 7), halt()];
    write_words(&mut bus, 0, &program);
    let mut cpu = K16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), K16Signal::Wait);
    assert_eq!(cpu.pc(), 2);
    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), K16Signal::Halt);
    assert_eq!(cpu.register(1), 7);
}

#[test]
fn k16_loads_and_stores_regular_ram_through_machine_bus() {
    let mut bus = MachineBus::new(64).unwrap();
    bus.store_i32(12, 0x0102_0304).unwrap();
    write_words(
        &mut bus,
        0,
        &[
            const4(1, 12),
            load32(2, 1),
            const4(3, 14),
            store32(3, 2),
            halt(),
        ],
    );
    let mut cpu = K16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), K16Signal::Halt,);
    assert_eq!(cpu.register(2), 0x0102_0304);
    assert_eq!(bus.load_i32(14).unwrap(), 0x0102_0304);
}

#[test]
fn k16_uses_r15_as_stack_pointer_into_guest_ram() {
    let mut bus = MachineBus::new(128).unwrap();
    let stack_top = 96;
    let mut program = Vec::new();
    program.extend(const32(K16_STACK_POINTER_REGISTER, stack_top));
    program.extend(const32(1, u32::MAX - 3));
    program.extend(const32(2, 0x1122_3344));
    program.push(const4(4, 4));
    program.extend(add(
        K16_STACK_POINTER_REGISTER,
        K16_STACK_POINTER_REGISTER,
        1,
    ));
    program.push(store32(K16_STACK_POINTER_REGISTER, 2));
    program.push(load32(3, K16_STACK_POINTER_REGISTER));
    program.extend(add(
        K16_STACK_POINTER_REGISTER,
        K16_STACK_POINTER_REGISTER,
        4,
    ));
    program.push(halt());
    write_words(&mut bus, 0, &program);
    let mut cpu = K16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 32).unwrap(), K16Signal::Halt,);
    assert_eq!(cpu.register(3), 0x1122_3344);
    assert_eq!(cpu.register(K16_STACK_POINTER_REGISTER as usize), stack_top);
    assert_eq!(bus.load_i32(stack_top - 4).unwrap() as u32, 0x1122_3344);
}

#[test]
fn k16_load8_and_store8_access_single_bytes_without_touching_neighbors() {
    let mut bus = MachineBus::new(64).unwrap();
    bus.store_u8(12, 0xaa).unwrap();
    bus.store_u8(13, 0xbb).unwrap();
    write_words(
        &mut bus,
        0,
        &[
            const4(1, 12),
            load8(2, 1),
            const4(3, 13),
            store8(3, 2),
            halt(),
        ],
    );
    let mut cpu = K16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), K16Signal::Halt,);
    assert_eq!(cpu.register(2), 0xaa);
    assert_eq!(bus.load_u8(12).unwrap(), 0xaa);
    assert_eq!(bus.load_u8(13).unwrap(), 0xaa);
    assert_eq!(bus.load_u8(14).unwrap(), 0);
}

#[test]
fn k16_loads_and_stores_mmio_through_machine_bus() {
    let mut bus = MachineBus::new(64).unwrap();
    let device_id = bus
        .map_mmio(12, Box::new(RegisterDevice { value: 7 }))
        .unwrap();
    write_words(
        &mut bus,
        0,
        &[
            const4(1, 12),
            load32(2, 1),
            const4(3, 9),
            store32(1, 3),
            halt(),
        ],
    );
    let mut cpu = K16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), K16Signal::Halt,);
    assert_eq!(cpu.register(2), 7);
    assert_eq!(bus.device::<RegisterDevice>(device_id).unwrap().value, 9);
}

#[test]
fn k16_loads_and_stores_high_mmio_through_machine_bus() {
    let mut bus = MachineBus::new(64).unwrap();
    let device_id = bus
        .map_mmio(0x1000_0400, Box::new(RegisterDevice { value: 7 }))
        .unwrap();
    let mut program = Vec::new();
    program.extend(const32(1, 0x1000_0400));
    program.push(load32(2, 1));
    program.push(const4(3, 9));
    program.push(store32(1, 3));
    program.push(halt());
    write_words(&mut bus, 0, &program);
    let mut cpu = K16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), K16Signal::Halt);
    assert_eq!(cpu.register(2), 7);
    assert_eq!(bus.device::<RegisterDevice>(device_id).unwrap().value, 9);
}

#[test]
fn k16_register_jump_sets_pc_to_guest_address() {
    let mut bus = MachineBus::new(64).unwrap();
    write_words(&mut bus, 0, &[const4(1, 6), jmp(1), const4(2, 1), halt()]);
    let mut cpu = K16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), K16Signal::Halt,);
    assert_eq!(cpu.register(2), 0);
    assert_eq!(cpu.pc(), 8);
}

#[test]
fn k16_call_pushes_return_pc_and_ret_restores_control_flow() {
    let mut bus = MachineBus::new(128).unwrap();
    let stack_top = 96;
    let function_pc = 18;
    let mut program = Vec::new();
    program.extend(const32(K16_STACK_POINTER_REGISTER, stack_top));
    program.extend(const32(1, function_pc));
    program.push(call(1));
    program.push(const4(3, 9));
    program.push(halt());
    program.push(const4(2, 7));
    program.push(ret());
    write_words(&mut bus, 0, &program);
    let mut cpu = K16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 32).unwrap(), K16Signal::Halt,);
    assert_eq!(cpu.register(2), 7);
    assert_eq!(cpu.register(3), 9);
    assert_eq!(cpu.register(K16_STACK_POINTER_REGISTER as usize), stack_top);
    assert_eq!(bus.load_i32(stack_top - 4).unwrap(), 14);
    assert_eq!(cpu.pc(), 18);
}

#[test]
fn k16_const32_consumes_extension_words_and_loads_u32_value() {
    let mut bus = MachineBus::new(64).unwrap();
    let mut program = Vec::new();
    program.extend(const32(1, 0x1000_0040));
    program.push(halt());
    write_words(&mut bus, 0, &program);
    let mut cpu = K16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), K16Signal::Halt,);
    assert_eq!(cpu.register(1), 0x1000_0040);
    assert_eq!(cpu.pc(), 8);
}

#[test]
fn k16_branch_if_zero_skips_guest_instruction() {
    let mut bus = MachineBus::new(64).unwrap();
    write_words(
        &mut bus,
        0,
        &[branch_if_zero(1, 1), const4(2, 9), const4(2, 4), halt()],
    );
    let mut cpu = K16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), K16Signal::Halt,);
    assert_eq!(cpu.register(2), 4);
}

#[test]
fn k16_branch_if_nonzero_can_loop_with_negative_relative_offset() {
    let mut bus = MachineBus::new(64).unwrap();
    let mut program = vec![const4(1, 3), const4(2, 1), const4(3, 0)];
    program.extend(const32(4, u32::MAX));
    program.extend(add(3, 3, 2));
    program.extend(add(1, 1, 4));
    program.extend([branch_if_nonzero(1, -5), halt()]);
    write_words(&mut bus, 0, &program);
    let mut cpu = K16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 32).unwrap(), K16Signal::Halt,);
    assert_eq!(cpu.register(1), 0);
    assert_eq!(cpu.register(3), 3);
}

#[test]
fn k16_eq_builds_condition_register_for_branching() {
    let mut bus = MachineBus::new(64).unwrap();
    let mut program = vec![const4(1, 7), const4(2, 7)];
    program.extend(eq(3, 1, 2));
    program.extend([branch_if_zero(3, 1), const4(4, 5), halt()]);
    write_words(&mut bus, 0, &program);
    let mut cpu = K16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), K16Signal::Halt,);
    assert_eq!(cpu.register(3), 1);
    assert_eq!(cpu.register(4), 5);
}

#[test]
fn k16_ltu_builds_unsigned_less_than_condition() {
    let mut bus = MachineBus::new(64).unwrap();
    let mut program = vec![const4(1, 2), const4(2, 5)];
    program.extend(ltu(3, 1, 2));
    program.extend([branch_if_zero(3, 1), const4(4, 7)]);
    program.extend([const4(1, 5), const4(2, 2)]);
    program.extend(ltu(3, 1, 2));
    program.extend([branch_if_nonzero(3, 1), const4(5, 9), halt()]);
    write_words(&mut bus, 0, &program);
    let mut cpu = K16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 64).unwrap(), K16Signal::Halt,);
    assert_eq!(cpu.register(4), 7);
    assert_eq!(cpu.register(5), 9);
}

#[test]
fn k16_executes_canonical_integer_register_ops() {
    let mut bus = MachineBus::new(128).unwrap();
    let mut program = Vec::new();
    program.extend(const32(1, 0x0000_00f0));
    program.extend(const32(2, 0x0000_000f));
    program.extend(sub(3, 1, 2));
    program.extend(and(4, 1, 2));
    program.extend(or(5, 1, 2));
    program.extend(xor(6, 1, 2));
    program.extend(ne(7, 1, 2));
    program.extend(ltu(8, 2, 1));
    program.extend(mul(9, 1, 2));
    program.extend(mulh_u(10, 1, 2));
    program.extend(const32(11, 0xffff_fffe));
    program.extend(const32(12, 2));
    program.extend(mulh_s(13, 11, 12));
    program.push(halt());
    write_words(&mut bus, 0, &program);
    let mut cpu = K16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 32).unwrap(), K16Signal::Halt,);
    assert_eq!(cpu.register(3), 0x0000_00e1);
    assert_eq!(cpu.register(4), 0x0000_0000);
    assert_eq!(cpu.register(5), 0x0000_00ff);
    assert_eq!(cpu.register(6), 0x0000_00ff);
    assert_eq!(cpu.register(7), 1);
    assert_eq!(cpu.register(8), 1);
    assert_eq!(cpu.register(9), 0x0000_0e10);
    assert_eq!(cpu.register(10), 0);
    assert_eq!(cpu.register(13), 0xffff_ffff);
}

#[test]
fn k16_executes_canonical_shift_and_signed_compare_ops() {
    let mut bus = MachineBus::new(128).unwrap();
    let mut program = Vec::new();
    program.extend(const32(1, 0x8000_0000));
    program.push(const4(2, 1));
    program.extend(shl(3, 2, 2));
    program.extend(shr(4, 1, 2));
    program.extend(sar(5, 1, 2));
    program.extend(lt_s(6, 1, 2));
    program.extend(lt_s(7, 2, 1));
    program.push(halt());
    write_words(&mut bus, 0, &program);
    let mut cpu = K16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 32).unwrap(), K16Signal::Halt,);
    assert_eq!(cpu.register(3), 2);
    assert_eq!(cpu.register(4), 0x4000_0000);
    assert_eq!(cpu.register(5), 0xc000_0000);
    assert_eq!(cpu.register(6), 1);
    assert_eq!(cpu.register(7), 0);
}

#[test]
fn k16_load16_and_store16_access_two_little_endian_bytes() {
    let mut bus = MachineBus::new(64).unwrap();
    bus.store_u8(12, 0x34).unwrap();
    bus.store_u8(13, 0x12).unwrap();
    bus.store_u8(14, 0xaa).unwrap();
    write_words(
        &mut bus,
        0,
        &[
            const4(1, 12),
            load16(2, 1),
            const4(3, 14),
            store16(3, 2),
            halt(),
        ],
    );
    let mut cpu = K16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), K16Signal::Halt,);
    assert_eq!(cpu.register(2), 0x1234);
    assert_eq!(bus.load_u8(12).unwrap(), 0x34);
    assert_eq!(bus.load_u8(13).unwrap(), 0x12);
    assert_eq!(bus.load_u8(14).unwrap(), 0x34);
    assert_eq!(bus.load_u8(15).unwrap(), 0x12);
}

#[test]
fn k16_test_bits_builds_condition_register_from_mask() {
    let mut bus = MachineBus::new(64).unwrap();
    let mut program = vec![const4(1, 0b1010)];
    program.extend(test_bits(2, 1, 0b1000));
    program.extend([branch_if_zero(2, 1), const4(3, 6), halt()]);
    write_words(&mut bus, 0, &program);
    let mut cpu = K16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), K16Signal::Halt,);
    assert_eq!(cpu.register(2), 1);
    assert_eq!(cpu.register(3), 6);
}

#[test]
fn k16_illegal_instruction_enters_configured_exception_vector() {
    let mut bus = MachineBus::new(64).unwrap();
    write_words(
        &mut bus,
        0,
        &[
            const4(1, 8),
            write_csr(K16_CSR_TRAP_VECTOR, 1),
            0xf123,
            halt(),
            read_csr(2, K16_CSR_TRAP_CAUSE),
            read_csr(3, K16_CSR_TRAP_PC),
            read_csr(4, K16_CSR_TRAP_VALUE),
            halt(),
        ],
    );
    let mut cpu = K16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), K16Signal::Halt,);
    assert_eq!(cpu.register(2), K16_TRAP_CAUSE_ILLEGAL_INSTRUCTION);
    assert_eq!(cpu.register(3), 4);
    assert_eq!(cpu.register(4), 0xf123);
}

#[test]
fn k16_unhandled_exception_is_a_hard_error() {
    let mut bus = MachineBus::new(64).unwrap();
    write_words(&mut bus, 0, &[0xf123]);
    let mut cpu = K16Cpu::new(0);

    let error = cpu.run_until_signal(&mut bus, 16).unwrap_err();

    assert!(
        error.to_string().contains("unhandled exception"),
        "unexpected error: {error}",
    );
}

#[test]
fn k16_timer_interrupt_enters_vector_and_iret_resumes_guest_pc() {
    let mut bus = MachineBus::new(128).unwrap();
    let handler_pc = 32;
    let mut program = Vec::new();
    program.extend(const32(1, handler_pc));
    program.push(write_csr(K16_CSR_TRAP_VECTOR, 1));
    program.push(const4(1, K16_INTERRUPT_SOURCE_TIMER0 as u8));
    program.push(write_csr(K16_CSR_INTERRUPT_MASK, 1));
    program.push(const4(1, 1));
    program.push(write_csr(K16_CSR_INTERRUPT_ENABLE, 1));
    program.push(const4(2, 9));
    program.push(halt());
    write_words(&mut bus, 0, &program);
    let mut handler = Vec::new();
    handler.push(read_csr(3, K16_CSR_TRAP_CAUSE));
    handler.extend(const32(8, 96));
    handler.push(store32(8, 3));
    handler.push(read_csr(4, K16_CSR_TRAP_PC));
    handler.extend(const32(8, 100));
    handler.push(store32(8, 4));
    handler.push(read_csr(5, K16_CSR_TRAP_VALUE));
    handler.extend(const32(8, 104));
    handler.push(store32(8, 5));
    handler.push(iret());
    write_words(&mut bus, handler_pc, &handler);
    let mut cpu = K16Cpu::new(0);

    assert_eq!(
        cpu.run_until_signal(&mut bus, 6).unwrap(),
        K16Signal::StepLimitExceeded,
    );
    assert_eq!(cpu.pc(), 16);

    cpu.request_interrupt(K16_INTERRUPT_SOURCE_TIMER0, 77);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), K16Signal::Halt);
    assert_eq!(cpu.register(2), 9);
    assert_eq!(
        bus.load_i32(96).unwrap() as u32,
        K16_TRAP_CAUSE_TIMER0_INTERRUPT
    );
    assert_eq!(bus.load_i32(100).unwrap(), 16);
    assert_eq!(bus.load_i32(104).unwrap(), 77);
    assert_eq!(cpu.csr(K16_CSR_INTERRUPT_PENDING), Some(0));
    assert_eq!(cpu.csr(K16_CSR_INTERRUPT_ENABLE), Some(1));
}

#[test]
fn k16_iret_restores_interrupted_stack_pointer() {
    let mut bus = MachineBus::new(64).unwrap();
    let mut program = Vec::new();
    program.extend(const32(1, 32));
    program.push(write_csr(K16_CSR_TRAP_VECTOR, 1));
    program.push(const4(1, K16_INTERRUPT_SOURCE_TIMER0 as u8));
    program.push(write_csr(K16_CSR_INTERRUPT_MASK, 1));
    program.push(const4(1, 1));
    program.push(write_csr(K16_CSR_INTERRUPT_ENABLE, 1));
    program.push(const4(2, 9));
    program.push(halt());
    program.extend([0; 6]);
    program.push(const4(K16_STACK_POINTER_REGISTER, 4));
    program.push(iret());
    write_words(&mut bus, 0, &program);
    let mut cpu = K16Cpu::new_with_stack(0, 60);

    assert_eq!(
        cpu.run_until_signal(&mut bus, 6).unwrap(),
        K16Signal::StepLimitExceeded,
    );

    cpu.request_interrupt(K16_INTERRUPT_SOURCE_TIMER0, 77);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), K16Signal::Halt);
    assert_eq!(cpu.register(2), 9);
    assert_eq!(cpu.register(usize::from(K16_STACK_POINTER_REGISTER)), 60);
}

#[test]
fn k16_syscall_enters_vector_and_iret_resumes_next_instruction() {
    let mut bus = MachineBus::new(128).unwrap();
    let handler_pc = 64;
    let mut program = Vec::new();
    program.extend(const32(1, handler_pc));
    program.push(write_csr(K16_CSR_TRAP_VECTOR, 1));
    program.push(const4(2, K16_CSR_TRAP_CAUSE as u8));
    program.push(syscall(2));
    program.push(const4(6, 9));
    program.push(halt());
    write_words(&mut bus, 0, &program);
    let mut handler = Vec::new();
    handler.push(read_csr(3, K16_CSR_TRAP_CAUSE));
    handler.extend(const32(8, 96));
    handler.push(store32(8, 3));
    handler.push(read_csr(4, K16_CSR_TRAP_PC));
    handler.extend(const32(8, 100));
    handler.push(store32(8, 4));
    handler.push(read_csr(5, K16_CSR_TRAP_VALUE));
    handler.extend(const32(8, 104));
    handler.push(store32(8, 5));
    handler.push(iret());
    write_words(&mut bus, handler_pc, &handler);
    let mut cpu = K16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), K16Signal::Halt);
    assert_eq!(
        bus.load_i32(96).unwrap() as u32,
        K16_TRAP_CAUSE_EXPLICIT_TRAP
    );
    assert_eq!(bus.load_i32(100).unwrap(), 12);
    assert_eq!(bus.load_i32(104).unwrap(), K16_CSR_TRAP_CAUSE as i32);
    assert_eq!(cpu.register(6), 9);
}

#[test]
fn k16_iret_preserves_interrupted_registers_and_returns_r0() {
    let mut bus = MachineBus::new(96).unwrap();
    let mut program = Vec::new();
    program.extend(const32(1, 48));
    program.push(write_csr(K16_CSR_TRAP_VECTOR, 1));
    program.extend(const32(5, 0x55aa_0005));
    program.extend(const32(6, 0x55aa_0006));
    program.push(const4(1, 7));
    program.push(syscall(1));
    program.push(halt());
    program.extend([0; 11]);
    program.push(const4(0, 9));
    program.push(const4(5, 1));
    program.push(const4(6, 2));
    program.push(iret());
    write_words(&mut bus, 0, &program);
    let mut cpu = K16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 32).unwrap(), K16Signal::Halt);
    assert_eq!(cpu.register(0), 9);
    assert_eq!(cpu.register(5), 0x55aa_0005);
    assert_eq!(cpu.register(6), 0x55aa_0006);
}

#[test]
fn k16_saved_trap_frame_csrs_can_override_iret_resume_context() {
    let mut bus = MachineBus::new(256).unwrap();
    let handler_pc = 96;
    let resume_pc = 160;
    let stack_top = 224;
    let mut program = Vec::new();
    program.extend(const32(1, handler_pc));
    program.push(write_csr(K16_CSR_TRAP_VECTOR, 1));
    program.extend(const32(K16_STACK_POINTER_REGISTER, 0x0000_00c0));
    program.extend(const32(2, 0x1111_2222));
    program.push(const4(1, 6));
    program.push(syscall(1));
    program.push(halt());
    write_words(&mut bus, 0, &program);

    let mut handler = Vec::new();
    handler.push(const4(7, 2));
    handler.push(write_csr(K16_CSR_TRAP_FRAME_INDEX, 7));
    handler.push(read_csr(3, K16_CSR_TRAP_FRAME_REGISTER));
    handler.extend(const32(8, 240));
    handler.push(store32(8, 3));
    handler.extend(const32(4, 0x3333_4444));
    handler.push(write_csr(K16_CSR_TRAP_FRAME_REGISTER, 4));
    handler.extend(const32(5, resume_pc));
    handler.push(write_csr(K16_CSR_TRAP_RESUME_PC, 5));
    handler.extend(const32(6, stack_top));
    handler.push(write_csr(K16_CSR_TRAP_STACK_POINTER, 6));
    handler.push(const4(7, 1));
    handler.push(write_csr(K16_CSR_TRAP_INTERRUPT_ENABLE, 7));
    handler.push(const4(0, 7));
    handler.push(iret());
    write_words(&mut bus, handler_pc, &handler);
    write_words(&mut bus, resume_pc, &[const4(9, 5), halt()]);
    let mut cpu = K16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 64).unwrap(), K16Signal::Halt);
    assert_eq!(bus.load_i32(240).unwrap() as u32, 0x1111_2222);
    assert_eq!(cpu.register(0), 7);
    assert_eq!(cpu.register(2), 0x3333_4444);
    assert_eq!(cpu.register(9), 5);
    assert_eq!(
        cpu.register(usize::from(K16_STACK_POINTER_REGISTER)),
        stack_top
    );
    assert_eq!(cpu.csr(K16_CSR_INTERRUPT_ENABLE), Some(1));
}

#[test]
fn k16_syscall_iret_restores_disabled_interrupt_state() {
    let mut bus = MachineBus::new(64).unwrap();
    let mut program = Vec::new();
    program.extend(const32(1, 32));
    program.push(write_csr(K16_CSR_TRAP_VECTOR, 1));
    program.push(const4(1, K16_INTERRUPT_SOURCE_TIMER0 as u8));
    program.push(write_csr(K16_CSR_INTERRUPT_MASK, 1));
    program.push(const4(1, 5));
    program.push(syscall(1));
    program.push(const4(6, 9));
    program.push(halt());
    program.extend([0; 7]);
    program.push(iret());
    write_words(&mut bus, 0, &program);
    let mut cpu = K16Cpu::new(0);

    cpu.request_interrupt(K16_INTERRUPT_SOURCE_TIMER0, 77);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), K16Signal::Halt);
    assert_eq!(cpu.register(6), 9);
    assert_eq!(cpu.csr(K16_CSR_INTERRUPT_ENABLE), Some(0));
    assert_eq!(
        cpu.csr(K16_CSR_INTERRUPT_PENDING),
        Some(K16_INTERRUPT_SOURCE_TIMER0),
    );
}

#[test]
fn k16_syscall_captures_arguments_from_r2_r3_r4() {
    let mut bus = MachineBus::new(256).unwrap();
    let handler_pc = 96;
    let mut program = Vec::new();
    program.extend(const32(1, handler_pc));
    program.push(write_csr(K16_CSR_TRAP_VECTOR, 1));
    program.push(const4(1, 3));
    program.extend(const32(2, 0x0000_0021));
    program.extend(const32(3, 0x0000_0022));
    program.extend(const32(4, 0x0000_0023));
    program.push(syscall(1));
    program.push(halt());
    write_words(&mut bus, 0, &program);
    let mut handler = Vec::new();
    handler.push(read_csr(3, K16_CSR_TRAP_VALUE));
    handler.extend(const32(8, 192));
    handler.push(store32(8, 3));
    handler.push(read_csr(4, K16_CSR_TRAP_ARG0));
    handler.extend(const32(8, 196));
    handler.push(store32(8, 4));
    handler.push(read_csr(5, K16_CSR_TRAP_ARG1));
    handler.extend(const32(8, 200));
    handler.push(store32(8, 5));
    handler.push(read_csr(6, K16_CSR_TRAP_ARG2));
    handler.extend(const32(8, 204));
    handler.push(store32(8, 6));
    handler.push(iret());
    write_words(&mut bus, handler_pc, &handler);
    let mut cpu = K16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 32).unwrap(), K16Signal::Halt);
    assert_eq!(bus.load_i32(192).unwrap(), 3);
    assert_eq!(bus.load_i32(196).unwrap(), 0x21);
    assert_eq!(bus.load_i32(200).unwrap(), 0x22);
    assert_eq!(bus.load_i32(204).unwrap(), 0x23);
}

#[test]
fn k16_iret_allows_helper_ret_and_caller_result_copy_before_pending_interrupt() {
    let mut bus = MachineBus::new(160).unwrap();
    let mut program = Vec::new();
    program.extend(const32(1, 112));
    program.push(write_csr(K16_CSR_TRAP_VECTOR, 1));
    program.push(const4(1, K16_INTERRUPT_SOURCE_TIMER0 as u8));
    program.push(write_csr(K16_CSR_INTERRUPT_MASK, 1));
    program.push(const4(1, 1));
    program.push(write_csr(K16_CSR_INTERRUPT_ENABLE, 1));
    program.push(const4(13, 0));
    program.push(const4(1, 5));
    program.extend(const32(14, 80));
    program.push(call(14));
    program.extend(add(6, 0, 13));
    program.push(halt());
    write_words(&mut bus, 0, &program);
    write_words(&mut bus, 80, &[syscall(1), ret()]);
    let trap_handler = [read_csr(0, K16_CSR_TRAP_CAUSE), iret()];
    write_words(&mut bus, 112, &trap_handler);
    let mut cpu = K16Cpu::new_with_stack(0, 128);

    for _ in 0..32 {
        if cpu.pc() == 112 {
            break;
        }
        assert!(cpu.step(&mut bus).unwrap().is_none());
    }
    assert_eq!(cpu.pc(), 112);

    cpu.request_interrupt(K16_INTERRUPT_SOURCE_TIMER0, 77);

    assert_eq!(cpu.run_until_signal(&mut bus, 32).unwrap(), K16Signal::Halt);
    assert_eq!(cpu.register(6), K16_TRAP_CAUSE_EXPLICIT_TRAP);
}

#[test]
fn k16_iret_allows_direct_caller_result_copy_before_pending_interrupt() {
    let mut bus = MachineBus::new(64).unwrap();
    let mut program = Vec::new();
    program.extend(const32(1, 32));
    program.push(write_csr(K16_CSR_TRAP_VECTOR, 1));
    program.push(const4(1, K16_INTERRUPT_SOURCE_TIMER0 as u8));
    program.push(write_csr(K16_CSR_INTERRUPT_MASK, 1));
    program.push(const4(1, 1));
    program.push(write_csr(K16_CSR_INTERRUPT_ENABLE, 1));
    program.push(const4(13, 0));
    program.push(const4(1, 5));
    program.push(syscall(1));
    program.extend(add(6, 0, 13));
    program.push(halt());
    program.extend([0; 4]);
    program.push(read_csr(0, K16_CSR_TRAP_CAUSE));
    program.push(iret());
    write_words(&mut bus, 0, &program);
    let mut cpu = K16Cpu::new(0);

    assert_eq!(
        cpu.run_until_signal(&mut bus, 10).unwrap(),
        K16Signal::StepLimitExceeded,
    );
    assert_eq!(cpu.pc(), 34);

    cpu.request_interrupt(K16_INTERRUPT_SOURCE_TIMER0, 77);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), K16Signal::Halt);
    assert_eq!(cpu.register(6), K16_TRAP_CAUSE_EXPLICIT_TRAP);
}

#[test]
fn k16_timer_interrupt_remains_pending_while_masked() {
    let mut bus = MachineBus::new(64).unwrap();
    write_words(
        &mut bus,
        0,
        &[
            const4(1, 24),
            write_csr(K16_CSR_TRAP_VECTOR, 1),
            const4(1, 0),
            write_csr(K16_CSR_INTERRUPT_MASK, 1),
            const4(1, 1),
            write_csr(K16_CSR_INTERRUPT_ENABLE, 1),
            read_csr(2, K16_CSR_INTERRUPT_PENDING),
            halt(),
            0,
            0,
            0,
            0,
            read_csr(3, K16_CSR_TRAP_CAUSE),
            halt(),
        ],
    );
    let mut cpu = K16Cpu::new(0);

    assert_eq!(
        cpu.run_until_signal(&mut bus, 6).unwrap(),
        K16Signal::StepLimitExceeded,
    );
    assert_eq!(cpu.pc(), 12);

    cpu.request_interrupt(K16_INTERRUPT_SOURCE_TIMER0, 99);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), K16Signal::Halt);
    assert_eq!(cpu.register(2), K16_INTERRUPT_SOURCE_TIMER0);
    assert_eq!(cpu.register(3), 0);
    assert_eq!(
        cpu.csr(K16_CSR_INTERRUPT_PENDING),
        Some(K16_INTERRUPT_SOURCE_TIMER0),
    );
}

fn write_words(bus: &mut MachineBus, address: u32, words: &[u16]) {
    for (index, word) in words.iter().copied().enumerate() {
        bus.store_u16(address + (index as u32 * 2), word).unwrap();
    }
}

fn const4(register: u8, value: u8) -> u16 {
    0x1000 | (u16::from(register) << 8) | u16::from(value & 0x0f)
}

fn const32(register: u8, value: u32) -> [u16; 3] {
    [
        0xe001 | (u16::from(register) << 8),
        (value & 0xffff) as u16,
        (value >> 16) as u16,
    ]
}

fn add(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x0, lhs, rhs)
}

fn sub(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x1, lhs, rhs)
}

fn and(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x2, lhs, rhs)
}

fn or(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x3, lhs, rhs)
}

fn xor(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x4, lhs, rhs)
}

fn eq(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x8, lhs, rhs)
}

fn ne(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x9, lhs, rhs)
}

fn ltu(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0xa, lhs, rhs)
}

fn lt_s(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0xb, lhs, rhs)
}

fn mul(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0xc, lhs, rhs)
}

fn mulh_u(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0xd, lhs, rhs)
}

fn mulh_s(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0xe, lhs, rhs)
}

fn shl(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x5, lhs, rhs)
}

fn shr(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x6, lhs, rhs)
}

fn sar(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    alu_rrr(dst, 0x7, lhs, rhs)
}

fn alu_rrr(dst: u8, subop: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    [
        0x2000 | (u16::from(dst) << 8) | u16::from(subop),
        (u16::from(lhs) << 4) | u16::from(rhs),
    ]
}

fn test_bits(dst: u8, src: u8, mask: u16) -> [u16; 2] {
    [0x3001 | (u16::from(dst) << 8) | (u16::from(src) << 4), mask]
}

fn load8(dst: u8, addr: u8) -> u16 {
    0x4000 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
}

fn load32(dst: u8, addr: u8) -> u16 {
    0x4002 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
}

fn load16(dst: u8, addr: u8) -> u16 {
    0x4001 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
}

fn store8(addr: u8, src: u8) -> u16 {
    0x5000 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

fn store32(addr: u8, src: u8) -> u16 {
    0x5002 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

fn store16(addr: u8, src: u8) -> u16 {
    0x5001 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

fn jmp(register: u8) -> u16 {
    0x7000 | (u16::from(register) << 8)
}

fn call(register: u8) -> u16 {
    0x8000 | (u16::from(register) << 8)
}

fn ret() -> u16 {
    0x9000
}

fn iret() -> u16 {
    0x0004
}

fn syscall(register: u8) -> u16 {
    0x0005 | (u16::from(register) << 8)
}

fn branch_if_zero(register: u8, offset_words: i8) -> u16 {
    0x6000 | (u16::from(register) << 8) | encode_signed_nibble(offset_words)
}

fn branch_if_nonzero(register: u8, offset_words: i8) -> u16 {
    0x6000 | (u16::from(register) << 8) | 0x0010 | encode_signed_nibble(offset_words)
}

fn read_csr(dst: u8, csr: u32) -> u16 {
    0x0002 | (u16::from(dst) << 8) | ((csr as u16) << 4)
}

fn write_csr(csr: u32, src: u8) -> u16 {
    0x0003 | ((csr as u16) << 8) | (u16::from(src) << 4)
}

fn encode_signed_nibble(value: i8) -> u16 {
    assert!((-8..=7).contains(&value));
    u16::from((value as i16 & 0x000f) as u8)
}

fn halt() -> u16 {
    0x0001
}

fn wait() -> u16 {
    0x0006
}

struct RegisterDevice {
    value: i32,
}

impl MmioDevice for RegisterDevice {
    fn size(&self) -> u32 {
        4
    }

    fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
        assert_eq!(offset, 0);
        Ok(self.value)
    }

    fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        assert_eq!(offset, 0);
        self.value = value;
        Ok(())
    }
}

struct YieldDevice {
    yield_requested: bool,
}

impl MmioDevice for YieldDevice {
    fn size(&self) -> u32 {
        16
    }

    fn take_yield_signal(&mut self) -> bool {
        let requested = self.yield_requested;
        self.yield_requested = false;
        requested
    }

    fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
        assert_eq!(offset, 12);
        Ok(i32::from(self.yield_requested))
    }

    fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        assert_eq!(offset, 12);
        self.yield_requested = value != 0;
        Ok(())
    }
}
