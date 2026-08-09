ENTRY(kernel)

SECTIONS
{
    . = 0x1000;
    .text : ALIGN(4)
    {
        KEEP(*(.text.kernel))
        . = ALIGN(4);
        *(.text .text.*)
        . = ALIGN(4);
    }

    /DISCARD/ :
    {
        *(.comment)
        *(.note*)
        *(.eh_frame*)
    }
}
