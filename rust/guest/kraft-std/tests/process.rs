#![cfg(feature = "host-test")]

#[test]
fn process_exit_has_never_returning_signature() {
    let _exit: fn(u32) -> ! = kraft_std::process::exit;
}
