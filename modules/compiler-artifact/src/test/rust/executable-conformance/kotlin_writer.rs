use std::{fs, sync::Arc};

use compukter_vm::{verify_artifact, ArtifactLimits};

#[test]
fn pinned_vm_verifies_kotlin_executable_instruction_artifact() {
    let path = std::env::var("COMPUKTER_KOTLIN_EXECUTABLE_ARTIFACT")
        .expect("Gradle conformance task must provide the generated Kotlin artifact");
    let bytes = fs::read(path).expect("Kotlin writer output must exist");

    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must decode and verify Kotlin writer output");

    assert_eq!(verified.module_count(), 2);
}
