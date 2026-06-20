#![cfg(feature = "host-test")]

use core::alloc::{GlobalAlloc, Layout};

use kraft_std::heap::{self, SbrkAllocator};

#[test]
fn heap_brk_delegates_to_runtime_syscall() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(0x0001_2000);

    let returned = heap::brk(0x0001_2000);

    assert_eq!(returned, Ok(0x0001_2000));
    assert_eq!(k16_rt::host_test::syscall_number(), k16_rt::host_test::BRK);
    assert_eq!(k16_rt::host_test::syscall_arg0(), 0x0001_2000);
}

#[test]
fn heap_sbrk_delegates_to_runtime_syscall() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(0x0001_1000);

    let returned = heap::sbrk(64);

    assert_eq!(returned, Ok(0x0001_1000));
    assert_eq!(k16_rt::host_test::syscall_number(), k16_rt::host_test::SBRK);
    assert_eq!(k16_rt::host_test::syscall_arg0(), 64);
}

#[test]
fn sbrk_allocator_returns_aligned_pointer_from_kernel_break() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(0x0001_1003);
    let layout = Layout::from_size_align(5, 4).expect("layout is valid");

    let ptr = unsafe { SbrkAllocator.alloc(layout) };

    assert_eq!(ptr as usize, 0x0001_1004);
    assert_eq!(k16_rt::host_test::syscall_number(), k16_rt::host_test::SBRK);
    assert_eq!(k16_rt::host_test::syscall_arg0(), 8);
}

#[test]
fn sbrk_allocator_returns_null_on_negative_syscall_status() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(k16_rt::host_test::ERROR_NO_MEMORY);
    let layout = Layout::from_size_align(5, 4).expect("layout is valid");

    let ptr = unsafe { SbrkAllocator.alloc(layout) };

    assert!(ptr.is_null());
}

#[test]
fn sbrk_allocator_returns_null_when_aligned_size_overflows() {
    k16_rt::host_test::reset_syscalls();
    k16_rt::host_test::set_syscall_return(0x0001_1003);
    let layout = Layout::from_size_align(u32::MAX as usize, 2).expect("layout is valid");

    let ptr = unsafe { SbrkAllocator.alloc(layout) };

    assert!(ptr.is_null());
    assert_eq!(k16_rt::host_test::syscall_number(), 0);
}
