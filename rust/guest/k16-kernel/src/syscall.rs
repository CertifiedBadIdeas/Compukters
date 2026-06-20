use k16_abi::syscall as abi_syscall;

use crate::{console, control, debug, fs, process, stdin, timer, trap, user_buffer};

const USER_IO_CHUNK_BYTES: usize = 256;
const MAX_OPEN_PATH_BYTES: usize = fs::MAX_OPEN_PATH_BYTES as usize;
const MAX_STAT_PATH_BYTES: usize = fs::MAX_STAT_PATH_BYTES as usize;

pub fn dispatch(number: u32) -> ! {
    match number {
        abi_syscall::DEBUG_MARKER => {
            debug::print_byte(b'S');
            control::set_ready();
            unsafe { k16_rt::iret_with_r0(abi_syscall::DEBUG_MARKER_RETURN) }
        }
        abi_syscall::DEBUG_WRITE_BYTE => {
            debug::print_byte((k16_rt::syscall_arg0() & 0xff) as u8);
            unsafe { k16_rt::iret_with_r0(abi_syscall::STATUS_OK) }
        }
        abi_syscall::EXIT => {
            let status = k16_rt::syscall_arg0();
            let exiting_pid = unsafe { process::current_process_slot() };
            unsafe { fs::close_file_fds_for_process(exiting_pid) };
            let mut resume = process::ParentResume::empty();
            if unsafe { process::finish_child_for_exit_into(status, &mut resume) }.is_ok() {
                if resume.wait_status_ptr != 0
                    && write_wait_status(resume.wait_status_ptr, status).is_err()
                {
                    resume.wait_status_ptr = 0;
                    resume.child_exit_status = abi_syscall::ERROR_FAULT;
                }
                if unsafe { process::destroy_exited_address_space(&resume) }.is_err() {
                    control::set_panic();
                    control::set_panic_code(abi_syscall::ERROR_FAULT as i32);
                    control::wait_forever()
                }
                unsafe { process::resume_parent_context(&resume) }
            }
            control::set_exit_code(status);
            control::set_halted();
            control::wait_forever()
        }
        abi_syscall::WRITE => {
            let fd = k16_rt::syscall_arg0();
            let ptr = k16_rt::syscall_arg1();
            let len = k16_rt::syscall_arg2();
            match write_fd(fd, ptr, len) {
                Ok(written) => unsafe { k16_rt::iret_with_r0(written) },
                Err(error) => unsafe { k16_rt::iret_with_r0(error) },
            }
        }
        abi_syscall::READ => {
            let fd = k16_rt::syscall_arg0();
            let ptr = k16_rt::syscall_arg1();
            let len = k16_rt::syscall_arg2();
            match read_fd(fd, ptr, len) {
                Ok(read) => unsafe { k16_rt::iret_with_r0(read) },
                Err(error) => unsafe { k16_rt::iret_with_r0(error) },
            }
        }
        abi_syscall::OPEN => {
            let ptr = k16_rt::syscall_arg0();
            let len = k16_rt::syscall_arg1();
            let flags = k16_rt::syscall_arg2();
            match open_fd(ptr, len, flags) {
                Ok(fd) => unsafe { k16_rt::iret_with_r0(fd) },
                Err(error) => unsafe { k16_rt::iret_with_r0(error) },
            }
        }
        abi_syscall::SEEK => {
            let fd = k16_rt::syscall_arg0();
            let offset = k16_rt::syscall_arg1();
            let whence = k16_rt::syscall_arg2();
            match seek_fd(fd, offset, whence) {
                Ok(new_offset) => unsafe { k16_rt::iret_with_r0(new_offset) },
                Err(error) => unsafe { k16_rt::iret_with_r0(error) },
            }
        }
        abi_syscall::READ_DIR => {
            let ptr = k16_rt::syscall_arg0();
            let len = k16_rt::syscall_arg1();
            match read_dir(ptr, len) {
                Ok(written) => unsafe { k16_rt::iret_with_r0(written) },
                Err(error) => unsafe { k16_rt::iret_with_r0(error) },
            }
        }
        abi_syscall::STAT => {
            let ptr = k16_rt::syscall_arg0();
            let len = k16_rt::syscall_arg1();
            let out_ptr = k16_rt::syscall_arg2();
            match stat_path(ptr, len, out_ptr) {
                Ok(()) => unsafe { k16_rt::iret_with_r0(abi_syscall::STATUS_OK) },
                Err(error) => unsafe { k16_rt::iret_with_r0(error) },
            }
        }
        abi_syscall::UNLINK => {
            let ptr = k16_rt::syscall_arg0();
            let len = k16_rt::syscall_arg1();
            match unlink_path(ptr, len) {
                Ok(()) => unsafe { k16_rt::iret_with_r0(abi_syscall::STATUS_OK) },
                Err(error) => unsafe { k16_rt::iret_with_r0(error) },
            }
        }
        abi_syscall::MKDIR => {
            let ptr = k16_rt::syscall_arg0();
            let len = k16_rt::syscall_arg1();
            match mkdir_path(ptr, len) {
                Ok(()) => unsafe { k16_rt::iret_with_r0(abi_syscall::STATUS_OK) },
                Err(error) => unsafe { k16_rt::iret_with_r0(error) },
            }
        }
        abi_syscall::RMDIR => {
            let ptr = k16_rt::syscall_arg0();
            let len = k16_rt::syscall_arg1();
            match rmdir_path(ptr, len) {
                Ok(()) => unsafe { k16_rt::iret_with_r0(abi_syscall::STATUS_OK) },
                Err(error) => unsafe { k16_rt::iret_with_r0(error) },
            }
        }
        abi_syscall::RENAME => {
            let ptr = k16_rt::syscall_arg0();
            let len = k16_rt::syscall_arg1();
            match rename_path(ptr, len) {
                Ok(()) => unsafe { k16_rt::iret_with_r0(abi_syscall::STATUS_OK) },
                Err(error) => unsafe { k16_rt::iret_with_r0(error) },
            }
        }
        abi_syscall::GAME_TICKS => {
            let out_ptr = k16_rt::syscall_arg0();
            match game_ticks(out_ptr) {
                Ok(()) => unsafe { k16_rt::iret_with_r0(abi_syscall::STATUS_OK) },
                Err(error) => unsafe { k16_rt::iret_with_r0(error) },
            }
        }
        abi_syscall::CLOSE => {
            let fd = k16_rt::syscall_arg0();
            match close_fd(fd) {
                Ok(()) => unsafe { k16_rt::iret_with_r0(abi_syscall::STATUS_OK) },
                Err(error) => unsafe { k16_rt::iret_with_r0(error) },
            }
        }
        abi_syscall::BRK => {
            let address = k16_rt::syscall_arg0();
            match set_program_break(address) {
                Ok(program_break) => unsafe { k16_rt::iret_with_r0(program_break) },
                Err(error) => unsafe { k16_rt::iret_with_r0(error) },
            }
        }
        abi_syscall::SBRK => {
            let delta = k16_rt::syscall_arg0();
            match grow_program_break(delta) {
                Ok(old_break) => unsafe { k16_rt::iret_with_r0(old_break) },
                Err(error) => unsafe { k16_rt::iret_with_r0(error) },
            }
        }
        abi_syscall::RUN => {
            let ptr = k16_rt::syscall_arg0();
            let len = k16_rt::syscall_arg1();
            let format = k16_rt::syscall_arg2();
            match prepare_run(ptr, len, format) {
                Ok(launch) => unsafe { process::enter_child_context(launch) },
                Err(error) => unsafe { k16_rt::iret_with_r0(error) },
            }
        }
        abi_syscall::SPAWN => {
            let ptr = k16_rt::syscall_arg0();
            let len = k16_rt::syscall_arg1();
            match spawn_argv(ptr, len) {
                Ok(pid) => unsafe { k16_rt::iret_with_r0(pid.raw()) },
                Err(error) => unsafe { k16_rt::iret_with_r0(error) },
            }
        }
        abi_syscall::WAIT => {
            let pid = k16_rt::syscall_arg0();
            let out_status = k16_rt::syscall_arg1();
            match wait_for_child(pid, out_status) {
                Ok(launch) => unsafe { process::enter_child_context(launch) },
                Err(error) => unsafe { k16_rt::iret_with_r0(error) },
            }
        }
        abi_syscall::YIELD => {
            k16_rt::yield_once();
            unsafe { k16_rt::iret_with_r0(abi_syscall::STATUS_OK) }
        }
        abi_syscall::SLEEP_TICKS => {
            timer::sleep_ticks(k16_rt::syscall_arg0());
            unsafe { k16_rt::iret_with_r0(abi_syscall::STATUS_OK) }
        }
        _ => trap::unknown_syscall(number),
    }
}

fn write_fd(fd: u32, ptr: u32, len: u32) -> Result<u32, u32> {
    match fd {
        abi_syscall::FD_STDOUT | abi_syscall::FD_STDERR => write_guest_bytes(ptr, len),
        abi_syscall::FD_STDIN => Err(abi_syscall::ERROR_BAD_FD),
        _ => write_file_fd(fd, ptr, len),
    }
}

fn write_file_fd(fd: u32, ptr: u32, len: u32) -> Result<u32, u32> {
    if len == 0 {
        return Ok(0);
    }
    if !valid_guest_buffer(ptr, len) {
        return Err(abi_syscall::ERROR_FAULT);
    }
    let mut total_written = 0;
    while total_written < len {
        let chunk_len = min_u32(len - total_written, USER_IO_CHUNK_BYTES as u32);
        let mut chunk = [0_u8; USER_IO_CHUNK_BYTES];
        user_buffer::copy_from_user_into(ptr + total_written, chunk_len, &mut chunk)?;
        match unsafe {
            fs::copy_ram_to_file_fd_range_for_process(
                current_process_id(),
                fd,
                chunk.as_ptr() as usize as u32,
                chunk_len,
            )
        } {
            Ok(written) => total_written += written,
            Err(error) => return Err(fs_error_to_status(error)),
        }
    }
    Ok(total_written)
}

fn seek_fd(fd: u32, offset: u32, whence: u32) -> Result<u32, u32> {
    match fd {
        abi_syscall::FD_STDIN | abi_syscall::FD_STDOUT | abi_syscall::FD_STDERR => {
            Err(abi_syscall::ERROR_BAD_FD)
        }
        _ => {
            match unsafe { fs::seek_file_fd_for_process(current_process_id(), fd, offset, whence) }
            {
                Ok(new_offset) => Ok(new_offset),
                Err(error) => Err(fs_error_to_status(error)),
            }
        }
    }
}

fn read_fd(fd: u32, ptr: u32, len: u32) -> Result<u32, u32> {
    if len == 0 {
        return Ok(0);
    }
    if !valid_guest_buffer(ptr, len) {
        return Err(abi_syscall::ERROR_FAULT);
    }
    match fd {
        abi_syscall::FD_STDIN => stdin::read(ptr, len),
        abi_syscall::FD_STDOUT | abi_syscall::FD_STDERR => Err(abi_syscall::ERROR_BAD_FD),
        _ => read_file_fd(fd, ptr, len),
    }
}

fn open_fd(ptr: u32, len: u32, flags: u32) -> Result<u32, u32> {
    if len == 0 || len > fs::MAX_OPEN_PATH_BYTES {
        return Err(abi_syscall::ERROR_INVALID);
    }
    let mut path = [0_u8; MAX_OPEN_PATH_BYTES];
    let path = user_buffer::copy_from_user_into(ptr, len, &mut path)?;
    match unsafe { fs::open_root_file_for_process(current_process_id(), path, flags) } {
        Ok(fd) => Ok(fd),
        Err(error) => Err(fs_error_to_status(error)),
    }
}

fn read_dir(ptr: u32, len: u32) -> Result<u32, u32> {
    if len < 16 || len > abi_syscall::MAX_READ_DIR_REQUEST_BYTES as u32 {
        return Err(abi_syscall::ERROR_INVALID);
    }
    let mut request = [0_u8; abi_syscall::MAX_READ_DIR_REQUEST_BYTES];
    let request = user_buffer::copy_from_user_into(ptr, len, &mut request)?;
    if read_u32_le(request, 0) != abi_syscall::READ_DIR_REQUEST_MAGIC {
        return Err(abi_syscall::ERROR_INVALID);
    }
    let path_len = read_u32_le(request, 4);
    if path_len == 0 || path_len > fs::MAX_READ_DIR_PATH_BYTES || len != 16 + path_len {
        return Err(abi_syscall::ERROR_INVALID);
    }
    let out_ptr = read_u32_le(request, 8);
    let out_len = read_u32_le(request, 12);
    if out_len != 0 && !valid_guest_buffer(out_ptr, out_len) {
        return Err(abi_syscall::ERROR_FAULT);
    }
    let path = &request[16..len as usize];
    let mut sink = UserDirectoryByteSink::new(out_ptr, out_len);
    match unsafe { fs::read_root_directory_into(path, &mut sink) } {
        Ok(written) => Ok(written),
        Err(error) => Err(fs_error_to_status(error)),
    }
}

fn stat_path(ptr: u32, len: u32, out_ptr: u32) -> Result<(), u32> {
    if len == 0 || len > fs::MAX_STAT_PATH_BYTES {
        return Err(abi_syscall::ERROR_INVALID);
    }
    let mut path = [0_u8; MAX_STAT_PATH_BYTES];
    let path = user_buffer::copy_from_user_into(ptr, len, &mut path)?;
    if !valid_guest_buffer(out_ptr, abi_syscall::STAT_METADATA_BYTES as u32) {
        return Err(abi_syscall::ERROR_FAULT);
    }
    let metadata = unsafe { fs::stat_root_path(path).map_err(fs_error_to_status)? };
    let mut metadata_bytes = [0_u8; abi_syscall::STAT_METADATA_BYTES];
    write_u32_le(&mut metadata_bytes, 0, metadata.file_type);
    write_u32_le(&mut metadata_bytes, 4, metadata.size_bytes);
    user_buffer::copy_to_user(out_ptr, &metadata_bytes)?;
    Ok(())
}

fn unlink_path(ptr: u32, len: u32) -> Result<(), u32> {
    if len == 0 || len > fs::MAX_STAT_PATH_BYTES {
        return Err(abi_syscall::ERROR_INVALID);
    }
    let mut path = [0_u8; MAX_STAT_PATH_BYTES];
    let path = user_buffer::copy_from_user_into(ptr, len, &mut path)?;
    match unsafe { fs::remove_root_file_for_process(path) } {
        Ok(()) => Ok(()),
        Err(error) => Err(fs_error_to_status(error)),
    }
}

fn mkdir_path(ptr: u32, len: u32) -> Result<(), u32> {
    if len == 0 || len > fs::MAX_STAT_PATH_BYTES {
        return Err(abi_syscall::ERROR_INVALID);
    }
    let mut path = [0_u8; MAX_STAT_PATH_BYTES];
    let path = user_buffer::copy_from_user_into(ptr, len, &mut path)?;
    match unsafe { fs::create_root_directory(path) } {
        Ok(()) => Ok(()),
        Err(error) => Err(fs_error_to_status(error)),
    }
}

fn rmdir_path(ptr: u32, len: u32) -> Result<(), u32> {
    if len == 0 || len > fs::MAX_STAT_PATH_BYTES {
        return Err(abi_syscall::ERROR_INVALID);
    }
    let mut path = [0_u8; MAX_STAT_PATH_BYTES];
    let path = user_buffer::copy_from_user_into(ptr, len, &mut path)?;
    match unsafe { fs::remove_root_directory(path) } {
        Ok(()) => Ok(()),
        Err(error) => Err(fs_error_to_status(error)),
    }
}

fn rename_path(ptr: u32, len: u32) -> Result<(), u32> {
    if len < 12 || len > abi_syscall::MAX_RENAME_REQUEST_BYTES as u32 {
        return Err(abi_syscall::ERROR_INVALID);
    }
    let mut request = [0_u8; abi_syscall::MAX_RENAME_REQUEST_BYTES];
    let request = user_buffer::copy_from_user_into(ptr, len, &mut request)?;
    if read_u32_le(request, 0) != abi_syscall::RENAME_REQUEST_MAGIC {
        return Err(abi_syscall::ERROR_INVALID);
    }
    let old_path_len = read_u32_le(request, 4);
    let new_path_len = read_u32_le(request, 8);
    if old_path_len == 0
        || new_path_len == 0
        || old_path_len > fs::MAX_STAT_PATH_BYTES
        || new_path_len > fs::MAX_STAT_PATH_BYTES
        || len != 12 + old_path_len + new_path_len
    {
        return Err(abi_syscall::ERROR_INVALID);
    }
    let old_path_start = 12;
    let new_path_start = old_path_start + old_path_len as usize;
    let old_path = &request[old_path_start..new_path_start];
    let new_path = &request[new_path_start..len as usize];
    match unsafe { fs::rename_root_file_for_process(current_process_id(), old_path, new_path) } {
        Ok(()) => Ok(()),
        Err(error) => Err(fs_error_to_status(error)),
    }
}

fn game_ticks(out_ptr: u32) -> Result<(), u32> {
    if !valid_guest_buffer(out_ptr, abi_syscall::GAME_TICKS_BYTES as u32) {
        return Err(abi_syscall::ERROR_FAULT);
    }
    let mut low = 0;
    let mut high = 0;
    timer::read_game_ticks_words(&mut low, &mut high);
    let mut bytes = [0_u8; abi_syscall::GAME_TICKS_BYTES];
    write_u32_le(&mut bytes, 0, low);
    write_u32_le(&mut bytes, 4, high);
    user_buffer::copy_to_user(out_ptr, &bytes)?;
    Ok(())
}

fn close_fd(fd: u32) -> Result<(), u32> {
    match unsafe { fs::close_file_fd_for_process(current_process_id(), fd) } {
        Ok(()) => Ok(()),
        Err(error) => Err(fs_error_to_status(error)),
    }
}

fn current_process_id() -> u32 {
    unsafe { process::current_process_slot() }
}

fn set_program_break(address: u32) -> Result<u32, u32> {
    unsafe { process::set_current_program_break(address).map_err(process::heap_status_from_error) }
}

fn grow_program_break(delta: u32) -> Result<u32, u32> {
    unsafe { process::grow_current_program_break(delta).map_err(process::heap_status_from_error) }
}

fn prepare_run(ptr: u32, len: u32, format: u32) -> Result<process::ChildLaunch, u32> {
    match format {
        abi_syscall::RUN_FORMAT_PATH => {
            if len == 0 || len > process::MAX_RUN_PATH_BYTES as u32 {
                return Err(abi_syscall::ERROR_INVALID);
            }
            let mut bytes = [0_u8; process::MAX_RUN_PATH_BYTES];
            let bytes = user_buffer::copy_from_user_into(ptr, len, &mut bytes)?;
            unsafe { process::begin_loaded_child_from_path(bytes) }
        }
        abi_syscall::RUN_FORMAT_ARGV => {
            if len == 0 || len > k16_abi::syscall::MAX_RUN_ARGV_REQUEST_BYTES as u32 {
                return Err(abi_syscall::ERROR_INVALID);
            }
            let mut bytes = [0_u8; k16_abi::syscall::MAX_RUN_ARGV_REQUEST_BYTES];
            let bytes = user_buffer::copy_from_user_into(ptr, len, &mut bytes)?;
            unsafe { process::begin_loaded_child_from_argv_request(bytes) }
        }
        _ => Err(abi_syscall::ERROR_INVALID),
    }
}

fn spawn_argv(ptr: u32, len: u32) -> Result<process::ProcessId, u32> {
    if len == 0 || len > k16_abi::syscall::MAX_SPAWN_ARGV_REQUEST_BYTES as u32 {
        return Err(abi_syscall::ERROR_INVALID);
    }
    let mut bytes = [0_u8; k16_abi::syscall::MAX_SPAWN_ARGV_REQUEST_BYTES];
    let bytes = user_buffer::copy_from_user_into(ptr, len, &mut bytes)?;
    unsafe { process::spawn_loaded_child_from_argv_request(bytes) }
}

fn wait_for_child(pid: u32, out_status: u32) -> Result<process::ChildLaunch, u32> {
    if !valid_guest_buffer(out_status, 4) {
        return Err(abi_syscall::ERROR_FAULT);
    }
    unsafe { process::wait_for_child_from_syscall(process::ProcessId::from_raw(pid), out_status) }
}

pub(crate) fn write_wait_status(out_status: u32, status: u32) -> Result<(), u32> {
    user_buffer::copy_to_user(out_status, &status.to_le_bytes()).map(|_| ())
}

fn write_guest_bytes(ptr: u32, len: u32) -> Result<u32, u32> {
    if len == 0 {
        return Ok(0);
    }
    let mut written = 0;
    while written < len {
        let chunk_len = min_u32(len - written, USER_IO_CHUNK_BYTES as u32);
        let mut chunk = [0_u8; USER_IO_CHUNK_BYTES];
        let chunk = user_buffer::copy_from_user_into(ptr + written, chunk_len, &mut chunk)?;
        for byte in chunk {
            console::write_byte(*byte);
        }
        written += chunk_len;
    }
    console::flush();
    Ok(len)
}

fn valid_guest_buffer(ptr: u32, len: u32) -> bool {
    user_buffer::valid_user_buffer(ptr, len)
}

fn read_u32_le(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes([
        bytes[offset],
        bytes[offset + 1],
        bytes[offset + 2],
        bytes[offset + 3],
    ])
}

fn write_u32_le(dst: &mut [u8], offset: usize, value: u32) {
    let src = value.to_le_bytes();
    let mut index = 0;
    while index < src.len() {
        dst[offset + index] = src[index];
        index += 1;
    }
}

fn read_file_fd(fd: u32, ptr: u32, len: u32) -> Result<u32, u32> {
    let mut total_read = 0;
    while total_read < len {
        let chunk_len = min_u32(len - total_read, USER_IO_CHUNK_BYTES as u32);
        let mut chunk = [0_u8; USER_IO_CHUNK_BYTES];
        let read = match unsafe {
            fs::copy_file_fd_range_to_ram_for_process(
                current_process_id(),
                fd,
                chunk.as_mut_ptr() as usize as u32,
                chunk_len,
            )
        } {
            Ok(read) => read,
            Err(error) => return Err(fs_error_to_status(error)),
        };
        if read == 0 {
            return Ok(total_read);
        }
        user_buffer::copy_to_user(ptr + total_read, &chunk[..read as usize])?;
        match unsafe { fs::advance_file_fd_for_process(current_process_id(), fd, read) } {
            Ok(()) => {}
            Err(error) => return Err(fs_error_to_status(error)),
        }
        total_read += read;
        if read < chunk_len {
            return Ok(total_read);
        }
    }
    Ok(total_read)
}

fn min_u32(left: u32, right: u32) -> u32 {
    if left < right {
        left
    } else {
        right
    }
}

struct UserDirectoryByteSink {
    ptr: u32,
    len: u32,
    written: u32,
}

impl UserDirectoryByteSink {
    const fn new(ptr: u32, len: u32) -> Self {
        Self {
            ptr,
            len,
            written: 0,
        }
    }
}

impl fs::DirectoryByteSink for UserDirectoryByteSink {
    fn push_byte(&mut self, byte: u8) -> Result<(), fs::FsError> {
        if self.written >= self.len {
            return Err(fs::FsError::NoMemory);
        }
        let bytes = [byte];
        user_buffer::copy_to_user(self.ptr + self.written, &bytes).map_err(fs::FsError)?;
        self.written += 1;
        Ok(())
    }

    fn written(&self) -> u32 {
        self.written
    }
}

fn fs_error_to_status(error: fs::FsError) -> u32 {
    error.0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn process_memory_buffer_validation_uses_supplied_range() {
        let memory = process::ProcessMemory::new(0x0001_3000, 0x0001_9000).unwrap();

        assert!(memory.contains_buffer(0x0001_3000, 4));
        assert!(memory.contains_buffer(0x0001_8ffc, 4));
        assert!(!memory.contains_buffer(0x0001_2ffc, 4));
        assert!(!memory.contains_buffer(0x0001_8ffe, 4));
        assert!(!memory.contains_buffer(0xffff_fffc, 8));
    }
}
