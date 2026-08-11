OUTPUT_ARCH(riscv)
ENTRY(_start)

PHDRS
{
    text PT_LOAD FLAGS(5);
    rodata PT_LOAD FLAGS(4);
    data PT_LOAD FLAGS(6);
}

SECTIONS
{
    . = 0x0000;
    .text : ALIGN(4) { KEEP(*(.text.start)) *(.text .text.*) . = ALIGN(4); } :text
    . = ALIGN(0x1000);
    .rodata : ALIGN(4)
    {
        ck_batch_value = .;
        LONG(__ck_batch)
        *(.rodata .rodata.*)
        . = ALIGN(4);
    } :rodata
    . = ALIGN(0x1000);
    .data : ALIGN(4) { *(.sdata .sdata.*) *(.data .data.*) . = ALIGN(4); } :data
    .bss (NOLOAD) : ALIGN(16)
    {
        *(.sbss .sbss.*) *(.bss .bss.*) *(COMMON)
        . = ALIGN(16);
        . += 0x800;
        __stack_top = .;
    } :data
    __image_end = .;
    ASSERT(__image_end <= 0x4000, "C comparison exceeds product RAM")
    /DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) *(.riscv.attributes) }
}
