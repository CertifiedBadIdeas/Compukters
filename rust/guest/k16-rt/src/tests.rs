use crate::*;
use std::sync::{Arc, Barrier};
use std::thread;

#[test]
fn memcpy_copies_bytes_without_touching_return_value() {
    let mut dst = [0u8; 5];
    let src = [1u8, 2, 3, 4, 5];

    let returned = unsafe { k16_memcpy(dst.as_mut_ptr(), src.as_ptr(), src.len()) };

    assert_eq!(returned, dst.as_mut_ptr());
    assert_eq!(dst, src);
}

#[test]
fn memmove_handles_forward_overlap() {
    let mut bytes = *b"abcdef";

    unsafe {
        k16_memmove(bytes.as_mut_ptr().add(1), bytes.as_ptr(), 5);
    }

    assert_eq!(&bytes, b"aabcde");
}

#[test]
fn memmove_handles_backward_overlap() {
    let mut bytes = *b"abcdef";

    unsafe {
        k16_memmove(bytes.as_mut_ptr(), bytes.as_ptr().add(1), 5);
    }

    assert_eq!(&bytes, b"bcdeff");
}

#[test]
fn memset_writes_low_byte_of_value() {
    let mut bytes = [0u8; 4];

    let returned = unsafe { k16_memset(bytes.as_mut_ptr(), 0x12ab, bytes.len()) };

    assert_eq!(returned, bytes.as_mut_ptr());
    assert_eq!(bytes, [0xab; 4]);
}

#[test]
fn memcmp_returns_lexicographic_byte_difference() {
    let lhs = [1u8, 2, 4];
    let rhs = [1u8, 2, 7];

    assert!(unsafe { k16_memcmp(lhs.as_ptr(), rhs.as_ptr(), lhs.len()) } < 0);
    assert!(unsafe { k16_memcmp(rhs.as_ptr(), lhs.as_ptr(), lhs.len()) } > 0);
    assert_eq!(
        unsafe { k16_memcmp(lhs.as_ptr(), lhs.as_ptr(), lhs.len()) },
        0
    );
}

#[test]
fn abort_helper_has_c_abi_signature() {
    let _abort: extern "C" fn() -> ! = abort;
}

#[test]
fn unsigned_i64_division_helpers_match_rust_for_nonzero_divisors() {
    let cases = [
        (0u64, 1u64),
        (1, 1),
        (42, 5),
        (u32::MAX as u64 + 17, 19),
        (u64::MAX, u32::MAX as u64),
        (u64::MAX, u64::MAX),
    ];

    for (lhs, rhs) in cases {
        assert_eq!(k16_udiv64(lhs, rhs), lhs / rhs, "{lhs} / {rhs}");
        assert_eq!(k16_umod64(lhs, rhs), lhs % rhs, "{lhs} % {rhs}");
    }
}

#[test]
fn signed_i64_division_helpers_match_wrapping_rust_cases() {
    let cases = [
        (42i64, 5i64),
        (-42, 5),
        (42, -5),
        (-42, -5),
        (i64::MIN, 1),
        (i64::MIN, -1),
        (i64::MIN, 3),
        (i64::MAX, -7),
    ];

    for (lhs, rhs) in cases {
        assert_eq!(k16_div64(lhs, rhs), lhs.wrapping_div(rhs), "{lhs} / {rhs}");
        assert_eq!(k16_mod64(lhs, rhs), lhs.wrapping_rem(rhs), "{lhs} % {rhs}");
    }
}

#[test]
fn timer0_helpers_read_test_counters() {
    crate::time::reset_test_timer0();
    crate::time::set_test_timer0_game_ticks(42);
    crate::time::set_test_timer0_monotonic_nanos(9001);

    assert_eq!(timer0_game_ticks(), 42);
    assert_eq!(timer0_monotonic_nanos(), 9001);
}

#[test]
fn timer0_part_helpers_read_high_and_low_words() {
    crate::time::reset_test_timer0();
    crate::time::set_test_timer0_game_ticks(0x0000_0002_0000_002a);
    crate::time::set_test_timer0_monotonic_nanos(0x0000_0003_0000_004d);

    let game_ticks = timer0_game_ticks_parts();
    let monotonic_nanos = timer0_monotonic_nanos_parts();

    assert_eq!(game_ticks.high, 2);
    assert_eq!(game_ticks.low, 42);
    assert_eq!(monotonic_nanos.high, 3);
    assert_eq!(monotonic_nanos.low, 77);
}

#[test]
fn yield_frames_and_sleep_ticks_use_yield_boundaries() {
    crate::time::reset_test_timer0();

    yield_frames(2);

    assert_eq!(crate::control::test_yield_count(), 2);
    assert_eq!(timer0_game_ticks(), 2);

    sleep_ticks(3);

    assert_eq!(crate::control::test_yield_count(), 5);
    assert_eq!(timer0_game_ticks(), 5);
}

#[test]
fn wait_once_is_a_runtime_control_boundary() {
    crate::control::reset_test_yield_count();

    wait_once();

    assert_eq!(crate::control::test_yield_count(), 0);
}

#[test]
fn interrupt_helpers_update_test_csr_state() {
    crate::trap::reset_test_interrupts();

    unsafe {
        install_trap_vector(0x0000_1234);
        set_interrupt_mask(k16_abi::cpu::interrupt_source::TIMER0);
        enable_interrupts();
    }
    crate::trap::set_test_trap_state(
        k16_abi::cpu::trap_cause::TIMER0_INTERRUPT,
        0x0000_2000,
        k16_abi::cpu::interrupt_source::TIMER0,
    );

    assert_eq!(crate::trap::test_trap_vector(), 0x0000_1234);
    assert_eq!(
        crate::trap::test_interrupt_mask(),
        k16_abi::cpu::interrupt_source::TIMER0
    );
    assert_eq!(crate::trap::test_interrupt_enable(), 1);
    assert_eq!(trap_cause(), k16_abi::cpu::trap_cause::TIMER0_INTERRUPT);
    assert_eq!(trap_pc(), 0x0000_2000);
    assert_eq!(trap_value(), k16_abi::cpu::interrupt_source::TIMER0);
    assert_eq!(interrupt_pending(), k16_abi::cpu::interrupt_source::TIMER0);

    disable_interrupts();

    assert_eq!(crate::trap::test_interrupt_enable(), 0);
}

#[test]
fn trap_frame_helpers_copy_saved_resume_state() {
    crate::trap::reset_test_interrupts();
    let mut source = TrapFrame::default();
    source.registers[0] = 0x0000_00a0;
    source.registers[1] = 0x0000_00a1;
    source.registers[15] = 0x0000_00af;
    source.resume_pc = 0x0000_2000;
    source.stack_pointer = 0x0000_3000;
    source.interrupt_enable = 1;

    let restored_r0 = unsafe { restore_trap_frame(&source) };
    let mut saved = TrapFrame::default();
    save_trap_frame(&mut saved);

    assert_eq!(restored_r0, source.registers[0]);
    assert_eq!(saved, source);
}

#[test]
fn syscall_helper_records_test_syscall_number() {
    crate::trap::reset_test_interrupts();

    syscall_once(k16_abi::cpu::csr::TRAP_CAUSE);

    assert_eq!(
        crate::trap::test_syscall_number(),
        k16_abi::cpu::csr::TRAP_CAUSE
    );
}

#[test]
fn syscall0_returns_test_syscall_value() {
    crate::trap::reset_test_interrupts();
    crate::trap::set_test_syscall_return(0x53);

    let returned = syscall0(0x20);

    assert_eq!(crate::trap::test_syscall_number(), 0x20);
    assert_eq!(returned, 0x53);
}

#[test]
fn syscall1_records_argument_and_returns_test_syscall_value() {
    crate::trap::reset_test_interrupts();
    crate::trap::set_test_syscall_return(0);

    let returned = syscall1(0x30, 0x21);

    assert_eq!(crate::trap::test_syscall_number(), 0x30);
    assert_eq!(crate::trap::test_syscall_arg0(), 0x21);
    assert_eq!(syscall_arg0(), 0x21);
    assert_eq!(returned, 0);
}

#[test]
fn syscall3_records_arguments_and_returns_test_syscall_value() {
    crate::trap::reset_test_interrupts();
    crate::trap::set_test_syscall_return(7);

    let returned = syscall3(0x40, 0x11, 0x22, 0x33);

    assert_eq!(crate::trap::test_syscall_number(), 0x40);
    assert_eq!(crate::trap::test_syscall_arg0(), 0x11);
    assert_eq!(crate::trap::test_syscall_arg1(), 0x22);
    assert_eq!(crate::trap::test_syscall_arg2(), 0x33);
    assert_eq!(syscall_arg0(), 0x11);
    assert_eq!(syscall_arg1(), 0x22);
    assert_eq!(syscall_arg2(), 0x33);
    assert_eq!(returned, 7);
}

#[test]
fn host_test_syscall_state_is_isolated_between_threads() {
    let ready = Arc::new(Barrier::new(2));
    let overwritten = Arc::new(Barrier::new(2));

    let left_ready = Arc::clone(&ready);
    let left_overwritten = Arc::clone(&overwritten);
    let left = thread::spawn(move || {
        crate::trap::reset_test_interrupts();
        syscall1(0x30, 0x21);
        left_ready.wait();
        left_overwritten.wait();

        (
            crate::trap::test_syscall_number(),
            crate::trap::test_syscall_arg0(),
        )
    });

    let right_ready = Arc::clone(&ready);
    let right_overwritten = Arc::clone(&overwritten);
    let right = thread::spawn(move || {
        right_ready.wait();
        crate::trap::reset_test_interrupts();
        syscall3(0x40, 0x11, 0x22, 0x33);
        right_overwritten.wait();

        (
            crate::trap::test_syscall_number(),
            crate::trap::test_syscall_arg0(),
        )
    });

    assert_eq!(left.join().expect("left thread returns"), (0x30, 0x21));
    assert_eq!(right.join().expect("right thread returns"), (0x40, 0x11));
}

#[test]
fn write_syscall_uses_fd_pointer_and_length_arguments() {
    crate::trap::reset_test_interrupts();
    crate::trap::set_test_syscall_return(3);
    let bytes = *b"abc";

    let returned = write_syscall(k16_abi::syscall::FD_STDOUT, bytes.as_ptr(), bytes.len());

    assert_eq!(crate::trap::test_syscall_number(), k16_abi::syscall::WRITE);
    assert_eq!(
        crate::trap::test_syscall_arg0(),
        k16_abi::syscall::FD_STDOUT
    );
    assert_eq!(
        crate::trap::test_syscall_arg1(),
        bytes.as_ptr() as usize as u32
    );
    assert_eq!(crate::trap::test_syscall_arg2(), 3);
    assert_eq!(returned, 3);
}

#[test]
fn read_syscall_uses_fd_pointer_and_length_arguments() {
    crate::trap::reset_test_interrupts();
    crate::trap::set_test_syscall_return(3);
    let mut bytes = [0u8; 3];

    let returned = read_syscall(k16_abi::syscall::FD_STDIN, bytes.as_mut_ptr(), bytes.len());

    assert_eq!(crate::trap::test_syscall_number(), k16_abi::syscall::READ);
    assert_eq!(crate::trap::test_syscall_arg0(), k16_abi::syscall::FD_STDIN);
    assert_eq!(
        crate::trap::test_syscall_arg1(),
        bytes.as_mut_ptr() as usize as u32
    );
    assert_eq!(crate::trap::test_syscall_arg2(), 3);
    assert_eq!(returned, 3);
}

#[test]
fn run_syscall_uses_path_pointer_and_length_arguments() {
    crate::trap::reset_test_interrupts();
    crate::trap::set_test_syscall_return(17);
    let path = *b"/bin/hello.kx";

    let returned = run_syscall(path.as_ptr(), path.len());

    assert_eq!(crate::trap::test_syscall_number(), k16_abi::syscall::RUN);
    assert_eq!(
        crate::trap::test_syscall_arg0(),
        path.as_ptr() as usize as u32
    );
    assert_eq!(crate::trap::test_syscall_arg1(), path.len() as u32);
    assert_eq!(crate::trap::test_syscall_arg2(), 0);
    assert_eq!(returned, 17);
}

#[test]
fn run_argv_syscall_uses_request_pointer_length_and_format_arguments() {
    crate::trap::reset_test_interrupts();
    crate::trap::set_test_syscall_return(19);
    let request = *b"argv-request";

    let returned = run_argv_syscall(request.as_ptr(), request.len());

    assert_eq!(crate::trap::test_syscall_number(), k16_abi::syscall::RUN);
    assert_eq!(
        crate::trap::test_syscall_arg0(),
        request.as_ptr() as usize as u32
    );
    assert_eq!(crate::trap::test_syscall_arg1(), request.len() as u32);
    assert_eq!(
        crate::trap::test_syscall_arg2(),
        k16_abi::syscall::RUN_FORMAT_ARGV
    );
    assert_eq!(returned, 19);
}

#[test]
fn open_syscall_uses_path_pointer_length_and_flags_arguments() {
    crate::trap::reset_test_interrupts();
    crate::trap::set_test_syscall_return(3);
    let path = *b"/etc/motd";

    let returned = open_syscall(path.as_ptr(), path.len(), 0);

    assert_eq!(crate::trap::test_syscall_number(), k16_abi::syscall::OPEN);
    assert_eq!(
        crate::trap::test_syscall_arg0(),
        path.as_ptr() as usize as u32
    );
    assert_eq!(crate::trap::test_syscall_arg1(), path.len() as u32);
    assert_eq!(crate::trap::test_syscall_arg2(), 0);
    assert_eq!(returned, 3);
}

#[test]
fn read_dir_syscall_uses_request_pointer_length_and_reserved_argument() {
    crate::trap::reset_test_interrupts();
    crate::trap::set_test_syscall_return(24);
    let request = *b"read-dir-request";

    let returned = read_dir_syscall(request.as_ptr(), request.len());

    assert_eq!(
        crate::trap::test_syscall_number(),
        k16_abi::syscall::READ_DIR
    );
    assert_eq!(
        crate::trap::test_syscall_arg0(),
        request.as_ptr() as usize as u32
    );
    assert_eq!(crate::trap::test_syscall_arg1(), request.len() as u32);
    assert_eq!(crate::trap::test_syscall_arg2(), 0);
    assert_eq!(returned, 24);
}

#[test]
fn stat_syscall_uses_path_pointer_length_and_output_pointer_arguments() {
    crate::trap::reset_test_interrupts();
    crate::trap::set_test_syscall_return(k16_abi::syscall::STATUS_OK);
    let path = *b"/bin/cat.kx";
    let mut metadata = [0u8; k16_abi::syscall::STAT_METADATA_BYTES];

    let returned = stat_syscall(path.as_ptr(), path.len(), metadata.as_mut_ptr());

    assert_eq!(crate::trap::test_syscall_number(), k16_abi::syscall::STAT);
    assert_eq!(
        crate::trap::test_syscall_arg0(),
        path.as_ptr() as usize as u32
    );
    assert_eq!(crate::trap::test_syscall_arg1(), path.len() as u32);
    assert_eq!(
        crate::trap::test_syscall_arg2(),
        metadata.as_mut_ptr() as usize as u32
    );
    assert_eq!(returned, k16_abi::syscall::STATUS_OK);
}

#[test]
fn close_syscall_uses_fd_argument() {
    crate::trap::reset_test_interrupts();
    crate::trap::set_test_syscall_return(k16_abi::syscall::STATUS_OK);

    let returned = close_syscall(3);

    assert_eq!(crate::trap::test_syscall_number(), k16_abi::syscall::CLOSE);
    assert_eq!(crate::trap::test_syscall_arg0(), 3);
    assert_eq!(returned, k16_abi::syscall::STATUS_OK);
}

#[test]
fn brk_syscall_uses_address_argument() {
    crate::trap::reset_test_interrupts();
    crate::trap::set_test_syscall_return(0x0001_2000);

    let returned = brk_syscall(0x0001_2000);

    assert_eq!(crate::trap::test_syscall_number(), k16_abi::syscall::BRK);
    assert_eq!(crate::trap::test_syscall_arg0(), 0x0001_2000);
    assert_eq!(returned, 0x0001_2000);
}

#[test]
fn sbrk_syscall_uses_delta_argument() {
    crate::trap::reset_test_interrupts();
    crate::trap::set_test_syscall_return(0x0001_1000);

    let returned = sbrk_syscall(64);

    assert_eq!(crate::trap::test_syscall_number(), k16_abi::syscall::SBRK);
    assert_eq!(crate::trap::test_syscall_arg0(), 64);
    assert_eq!(returned, 0x0001_1000);
}

#[test]
fn debug_marker_uses_named_syscall_and_returns_marker_value() {
    crate::trap::reset_test_interrupts();
    crate::trap::set_test_syscall_return(k16_abi::syscall::DEBUG_MARKER_RETURN);

    let returned = debug_marker();

    assert_eq!(
        crate::trap::test_syscall_number(),
        k16_abi::syscall::DEBUG_MARKER
    );
    assert_eq!(returned, k16_abi::syscall::DEBUG_MARKER_RETURN);
}

#[test]
fn debug_write_byte_uses_named_syscall_and_low_byte_argument() {
    crate::trap::reset_test_interrupts();
    crate::trap::set_test_syscall_return(k16_abi::syscall::STATUS_OK);

    let returned = debug_write_byte(0x21);

    assert_eq!(
        crate::trap::test_syscall_number(),
        k16_abi::syscall::DEBUG_WRITE_BYTE
    );
    assert_eq!(crate::trap::test_syscall_arg0(), 0x21);
    assert_eq!(returned, k16_abi::syscall::STATUS_OK);
}

#[test]
fn yield_syscall_uses_named_syscall_and_returns_status_ok() {
    crate::trap::reset_test_interrupts();
    crate::trap::set_test_syscall_return(k16_abi::syscall::STATUS_OK);

    let returned = yield_syscall();

    assert_eq!(crate::trap::test_syscall_number(), k16_abi::syscall::YIELD);
    assert_eq!(returned, k16_abi::syscall::STATUS_OK);
}

#[test]
fn sleep_ticks_syscall_uses_named_syscall_and_tick_argument() {
    crate::trap::reset_test_interrupts();
    crate::trap::set_test_syscall_return(k16_abi::syscall::STATUS_OK);

    let returned = sleep_ticks_syscall(3);

    assert_eq!(
        crate::trap::test_syscall_number(),
        k16_abi::syscall::SLEEP_TICKS
    );
    assert_eq!(crate::trap::test_syscall_arg0(), 3);
    assert_eq!(returned, k16_abi::syscall::STATUS_OK);
}
