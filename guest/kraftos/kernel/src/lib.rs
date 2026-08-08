#![no_std]

#[cfg(test)]
extern crate std;

pub mod boot_chain;
pub mod fs;
pub mod image;
pub mod kfs;
pub mod memory_layout;
pub mod mmio;
pub mod os_stats;
pub mod page_alloc;
pub mod process;
pub mod trap_policy;
pub mod user_buffer;
pub mod vfs;

#[cfg(test)]
mod font;
#[cfg(test)]
mod generated;
#[cfg(test)]
mod gpu;
#[cfg(test)]
mod terminal_render;
