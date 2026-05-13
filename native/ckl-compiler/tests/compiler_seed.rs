use ckl_compiler::compile;

#[test]
fn compiler_exposes_public_compile_api() {
    let error = compile("").unwrap_err();

    assert!(error.message.contains("expected `fn`"), "{error:?}");
}
