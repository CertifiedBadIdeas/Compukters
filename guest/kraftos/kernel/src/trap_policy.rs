#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SynchronousTrapAction {
    KernelTrap,
    ExitCurrentChild(u32),
}

pub fn classify_synchronous_trap(
    cause: u32,
    _address_mode: u32,
    privilege_mode: u32,
) -> SynchronousTrapAction {
    if privilege_mode == k16_abi::cpu::trap_mode::PRIVILEGE_USER && is_user_memory_fault(cause) {
        SynchronousTrapAction::ExitCurrentChild(k16_abi::syscall::ERROR_FAULT)
    } else {
        SynchronousTrapAction::KernelTrap
    }
}

fn is_user_memory_fault(cause: u32) -> bool {
    matches!(
        cause,
        k16_abi::cpu::trap_cause::INSTRUCTION_FETCH_FAULT
            | k16_abi::cpu::trap_cause::LOAD_FAULT
            | k16_abi::cpu::trap_cause::STORE_FAULT
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use k16_abi::cpu::{trap_cause, trap_mode};

    #[test]
    fn user_memory_faults_exit_current_child() {
        assert_eq!(
            classify_synchronous_trap(
                trap_cause::LOAD_FAULT,
                trap_mode::ADDRESS_TRANSLATED,
                trap_mode::PRIVILEGE_USER,
            ),
            SynchronousTrapAction::ExitCurrentChild(k16_abi::syscall::ERROR_FAULT)
        );
        assert_eq!(
            classify_synchronous_trap(
                trap_cause::STORE_FAULT,
                trap_mode::ADDRESS_TRANSLATED,
                trap_mode::PRIVILEGE_USER,
            ),
            SynchronousTrapAction::ExitCurrentChild(k16_abi::syscall::ERROR_FAULT)
        );
        assert_eq!(
            classify_synchronous_trap(
                trap_cause::INSTRUCTION_FETCH_FAULT,
                trap_mode::ADDRESS_TRANSLATED,
                trap_mode::PRIVILEGE_USER,
            ),
            SynchronousTrapAction::ExitCurrentChild(k16_abi::syscall::ERROR_FAULT)
        );
    }

    #[test]
    fn kernel_memory_faults_remain_kernel_traps() {
        assert_eq!(
            classify_synchronous_trap(
                trap_cause::LOAD_FAULT,
                trap_mode::ADDRESS_PHYSICAL,
                trap_mode::PRIVILEGE_KERNEL,
            ),
            SynchronousTrapAction::KernelTrap
        );
    }
}
