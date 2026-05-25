use rux_vm::low_bus::{MachineBus, MmioDevice};
use rux_vm::low_machine::MemoryFault;
use rux_vm::rux16::{Rux16Cpu, Rux16Signal};

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
fn rux16_illegal_instruction_reports_trap() {
    let mut bus = MachineBus::new(64).unwrap();
    write_words(&mut bus, 0, &[0xf000]);
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(
        cpu.run_until_signal(&mut bus, 16).unwrap(),
        Rux16Signal::Trap,
    );
    assert!(
        cpu.trap().unwrap().contains("illegal instruction"),
        "unexpected trap: {:?}",
        cpu.trap(),
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

fn add(dst: u8, lhs: u8, rhs: u8) -> u16 {
    0x2000 | (u16::from(dst) << 8) | (u16::from(lhs) << 4) | u16::from(rhs)
}

fn load32(dst: u8, addr: u8) -> u16 {
    0x4002 | (u16::from(dst) << 8) | (u16::from(addr) << 4)
}

fn store32(addr: u8, src: u8) -> u16 {
    0x5002 | (u16::from(addr) << 8) | (u16::from(src) << 4)
}

fn jmp(register: u8) -> u16 {
    0x7000 | (u16::from(register) << 8)
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
