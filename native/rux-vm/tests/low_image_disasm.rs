use rux_vm::low_disasm::disassemble_image;
use rux_vm::low_image::{Function, Image, Instruction};

#[test]
fn disassembles_low_image_header_sections_and_instructions() {
    let image = Image {
        memory_size: 4096,
        rodata: vec![0x52, 0x55, 0x58],
        data: vec![0x01, 0x02],
        bss_size: 16,
        entry_function_index: 0,
        functions: vec![
            Function {
                name: "main".to_string(),
                register_count: 4,
                parameters: vec![0],
                instructions: vec![
                    Instruction::I32Const { dst: 1, value: 40 },
                    Instruction::I32Const { dst: 2, value: 2 },
                    Instruction::I32Add {
                        dst: 3,
                        lhs: 1,
                        rhs: 2,
                    },
                    Instruction::CallStatic {
                        return_register: None,
                        function_index: 1,
                        arguments: vec![3],
                    },
                    Instruction::ReturnI32 { src: 3 },
                ],
            },
            Function {
                name: "debug_write".to_string(),
                register_count: 1,
                parameters: vec![0],
                instructions: vec![Instruction::ReturnUnit],
            },
        ],
    };

    let disassembly = disassemble_image(&image);

    assert!(disassembly.contains("; RUXI v1"));
    assert!(disassembly.contains("memory_size 4096"));
    assert!(disassembly.contains("rodata_size 3"));
    assert!(disassembly.contains("data_size 2"));
    assert!(disassembly.contains("bss_size 16"));
    assert!(disassembly.contains("entry fn0"));
    assert!(disassembly.contains("fn 0 main(regs=4, params=[r0]) {"));
    assert!(disassembly.contains("  0000: I32Const r1, 40"));
    assert!(disassembly.contains("  0002: I32Add r3, r1, r2"));
    assert!(disassembly.contains("  0003: CallStatic _, fn1, [r3]"));
    assert!(disassembly.contains("  0004: ReturnI32 r3"));
    assert!(disassembly.contains("fn 1 debug_write(regs=1, params=[r0]) {"));
    assert!(disassembly.contains("  0000: ReturnUnit"));
}
