use crate::*;

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

    let returned = syscall0(2);

    assert_eq!(crate::trap::test_syscall_number(), 2);
    assert_eq!(returned, 0x53);
}
