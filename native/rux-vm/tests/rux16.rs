use rux_vm::low_bus::{MachineBus, MmioDevice};
use rux_vm::low_machine::MemoryFault;
use rux_vm::rux16::{
    Rux16Cpu, Rux16Signal, RUX16_CSR_TRAP_CAUSE, RUX16_CSR_TRAP_PC, RUX16_CSR_TRAP_VALUE,
    RUX16_CSR_TRAP_VECTOR, RUX16_TRAP_CAUSE_ILLEGAL_INSTRUCTION,
};

#[test]
fn rux16_fetches_decodes_and_executes_words_from_guest_memory() {
    let mut bus = MachineBus::new(64).unwrap();
    write_words(
        &mut bus,
        0,
        &[const4(1, 2), const4(2, 5), add(3, 1, 2), halt()],
    );
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(
        cpu.run_until_signal(&mut bus, 16).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(cpu.register(3), 7);
    assert_eq!(cpu.pc(), 8);
}

#[test]
fn rux16_loads_and_stores_regular_ram_through_machine_bus() {
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
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(
        cpu.run_until_signal(&mut bus, 16).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(cpu.register(2), 0x0102_0304);
    assert_eq!(bus.load_i32(14).unwrap(), 0x0102_0304);
}

#[test]
fn rux16_load8_and_store8_access_single_bytes_without_touching_neighbors() {
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
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(
        cpu.run_until_signal(&mut bus, 16).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(cpu.register(2), 0xaa);
    assert_eq!(bus.load_u8(12).unwrap(), 0xaa);
    assert_eq!(bus.load_u8(13).unwrap(), 0xaa);
    assert_eq!(bus.load_u8(14).unwrap(), 0);
}

#[test]
fn rux16_loads_and_stores_mmio_through_machine_bus() {
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
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(
        cpu.run_until_signal(&mut bus, 16).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(cpu.register(2), 7);
    assert_eq!(bus.device::<RegisterDevice>(device_id).unwrap().value, 9);
}

#[test]
fn rux16_register_jump_sets_pc_to_guest_address() {
    let mut bus = MachineBus::new(64).unwrap();
    write_words(&mut bus, 0, &[const4(1, 6), jmp(1), const4(2, 1), halt()]);
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(
        cpu.run_until_signal(&mut bus, 16).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(cpu.register(2), 0);
    assert_eq!(cpu.pc(), 8);
}

#[test]
fn rux16_const32_consumes_extension_words_and_loads_u32_value() {
    let mut bus = MachineBus::new(64).unwrap();
    let mut program = Vec::new();
    program.extend(const32(1, 0x1000_0040));
    program.push(halt());
    write_words(&mut bus, 0, &program);
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(
        cpu.run_until_signal(&mut bus, 16).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(cpu.register(1), 0x1000_0040);
    assert_eq!(cpu.pc(), 8);
}

#[test]
fn rux16_branch_if_zero_skips_guest_instruction() {
    let mut bus = MachineBus::new(64).unwrap();
    write_words(
        &mut bus,
        0,
        &[branch_if_zero(1, 1), const4(2, 9), const4(2, 4), halt()],
    );
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(
        cpu.run_until_signal(&mut bus, 16).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(cpu.register(2), 4);
}

#[test]
fn rux16_branch_if_nonzero_can_loop_with_negative_relative_offset() {
    let mut bus = MachineBus::new(64).unwrap();
    let mut program = vec![const4(1, 3), const4(2, 1), const4(3, 0)];
    program.extend(const32(4, u32::MAX));
    program.extend([add(3, 3, 2), add(1, 1, 4), branch_if_nonzero(1, -3), halt()]);
    write_words(&mut bus, 0, &program);
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(
        cpu.run_until_signal(&mut bus, 32).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(cpu.register(1), 0);
    assert_eq!(cpu.register(3), 3);
}

#[test]
fn rux16_eq_builds_condition_register_for_branching() {
    let mut bus = MachineBus::new(64).unwrap();
    let mut program = vec![const4(1, 7), const4(2, 7)];
    program.extend(eq(3, 1, 2));
    program.extend([branch_if_zero(3, 1), const4(4, 5), halt()]);
    write_words(&mut bus, 0, &program);
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(
        cpu.run_until_signal(&mut bus, 16).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(cpu.register(3), 1);
    assert_eq!(cpu.register(4), 5);
}

#[test]
fn rux16_ltu_builds_unsigned_less_than_condition() {
    let mut bus = MachineBus::new(64).unwrap();
    let mut program = vec![const4(1, 2), const4(2, 5)];
    program.extend(ltu(3, 1, 2));
    program.extend([branch_if_zero(3, 1), const4(4, 7)]);
    program.extend([const4(1, 5), const4(2, 2)]);
    program.extend(ltu(3, 1, 2));
    program.extend([branch_if_nonzero(3, 1), const4(5, 9), halt()]);
    write_words(&mut bus, 0, &program);
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(
        cpu.run_until_signal(&mut bus, 64).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(cpu.register(4), 7);
    assert_eq!(cpu.register(5), 9);
}

#[test]
fn rux16_test_bits_builds_condition_register_from_mask() {
    let mut bus = MachineBus::new(64).unwrap();
    let mut program = vec![const4(1, 0b1010)];
    program.extend(test_bits(2, 1, 0b1000));
    program.extend([branch_if_zero(2, 1), const4(3, 6), halt()]);
    write_words(&mut bus, 0, &program);
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(
        cpu.run_until_signal(&mut bus, 16).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(cpu.register(2), 1);
    assert_eq!(cpu.register(3), 6);
}

#[test]
fn rux16_illegal_instruction_enters_configured_exception_vector() {
    let mut bus = MachineBus::new(64).unwrap();
    write_words(
        &mut bus,
        0,
        &[
            const4(1, 8),
            write_csr(RUX16_CSR_TRAP_VECTOR, 1),
            0xf123,
            halt(),
            read_csr(2, RUX16_CSR_TRAP_CAUSE),
            read_csr(3, RUX16_CSR_TRAP_PC),
            read_csr(4, RUX16_CSR_TRAP_VALUE),
            halt(),
        ],
    );
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(
        cpu.run_until_signal(&mut bus, 16).unwrap(),
        Rux16Signal::Halt,
    );
    assert_eq!(cpu.register(2), RUX16_TRAP_CAUSE_ILLEGAL_INSTRUCTION);
    assert_eq!(cpu.register(3), 4);
    assert_eq!(cpu.register(4), 0xf123);
}

#[test]
fn rux16_unhandled_exception_is_a_hard_error() {
    let mut bus = MachineBus::new(64).unwrap();
    write_words(&mut bus, 0, &[0xf123]);
    let mut cpu = Rux16Cpu::new(0);

    let error = cpu.run_until_signal(&mut bus, 16).unwrap_err();

    assert!(
        error.to_string().contains("unhandled exception"),
        "unexpected error: {error}",
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

fn add(dst: u8, lhs: u8, rhs: u8) -> u16 {
    0x2000 | (u16::from(dst) << 8) | (u16::from(lhs) << 4) | u16::from(rhs)
}

fn eq(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    [
        0x3000 | (u16::from(dst) << 8),
        (u16::from(lhs) << 4) | u16::from(rhs),
    ]
}

fn ltu(dst: u8, lhs: u8, rhs: u8) -> [u16; 2] {
    [
        0x3002 | (u16::from(dst) << 8),
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

fn store8(addr: u8, src: u8) -> u16 {
    0x5000 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

fn store32(addr: u8, src: u8) -> u16 {
    0x5002 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

fn jmp(register: u8) -> u16 {
    0x7000 | (u16::from(register) << 8)
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
