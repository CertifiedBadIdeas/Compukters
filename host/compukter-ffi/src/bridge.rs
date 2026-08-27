use std::path::PathBuf;
use std::sync::{Arc, OnceLock};

use compukter_vm::{
    verify_artifact, AdmissionError, ArtifactLimits, CanonicalLineSubmissionError,
    CompilationRequest, ComputerAdvanceOutcome, ComputerError, ComputerId, ComputerMachine,
    ComputerStartError, ComputerValue, DeploymentCandidate, EntryArgumentLimits,
    ExecutableRevision, ExecutionProfile, FileCapability, FileRights, FileSystemError,
    FileSystemLimits, GuestTrap, HostDeployError, HostFailure, HostResponse, HostValueInput,
    HostVerifyError, ManagedAllocationFailure, ProcessFailureReason, ProcessLimits,
    QuotaExhaustion, ResumeError, RomImage, RunError, StoreError, StoreHealth, StoreOpenError,
    TerminalDevice, TerminalInputError, TerminalKey, TerminalKeyAction, TerminalKeyEvent,
    TerminalModifiers, TerminalUpdate, VirtualPath, VmFault, WorldFileSystemStore,
};

use crate::handle_table::{HandleError, HandleTable};

static SESSIONS: OnceLock<HandleTable<BridgeSession>> = OnceLock::new();
static STORES: OnceLock<HandleTable<Arc<WorldFileSystemStore>>> = OnceLock::new();
static DEPLOYMENT_CANDIDATES: OnceLock<HandleTable<Box<DeploymentCandidate>>> = OnceLock::new();

#[derive(Debug)]
pub(crate) enum CreateError {
    Verification,
    Admission(AdmissionError),
    Run(RunError),
    Process(ProcessFailureReason),
    Handle(HandleError),
}

#[derive(Debug)]
pub(crate) enum CreateInStoreError {
    Create(CreateError),
    Rom,
    Store(StoreBridgeError),
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) enum OwnedValue {
    I32(i32),
    I64(i64),
    F32(u32),
    F64(u64),
    Bool(bool),
    Char(u16),
    String(Vec<u16>),
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct OwnedRequest {
    pub id: u64,
    pub namespace: String,
    pub name: String,
    pub abi_major: u16,
    pub abi_minor: u16,
    pub operation: u32,
    pub arguments: Vec<OwnedValue>,
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) enum OwnedOutcome {
    SliceExhausted,
    HostRequest(OwnedRequest),
    AllocationExhausted(ManagedAllocationFailure),
    QuotaExhausted(QuotaExhaustion),
    Halted(Option<OwnedValue>),
    Crashed(GuestTrap),
    Faulted(VmFault),
    HostFailed(HostFailure),
    WaitingForTerminalEvent,
    CompilationRequested { token: u64 },
}

#[derive(Debug)]
struct BridgeSession {
    computer: ComputerMachine,
    compilation: Option<CompilationRequest>,
    filesystem_limits: FileSystemLimits,
}

impl BridgeSession {
    fn new(computer: ComputerMachine, filesystem_limits: FileSystemLimits) -> Self {
        Self {
            computer,
            compilation: None,
            filesystem_limits,
        }
    }
}

#[derive(Debug)]
pub(crate) enum DeploymentVerifyBridgeError {
    Session(HandleError),
    Verification,
    Admission,
    Candidate(HandleError),
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum DeploymentBridgeError {
    Session(HandleError),
    Candidate(HandleError),
    WrongMachine,
    ProfileChanged,
    FileSystem(FileSystemError),
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum RevisionBridgeError {
    Session(HandleError),
    FileSystem(FileSystemError),
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) enum OwnedResponse {
    SuccessUnit,
    SuccessString(Vec<u16>),
    Failure(HostFailure),
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum BridgeError {
    Handle(HandleError),
    Run(RunError),
    Resume(ResumeError),
    InvalidRequestId,
    InvalidOperation,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum StoreBridgeError {
    Handle(HandleError),
    Store(StoreError),
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum StoreCreateError {
    Open(StoreOpenError),
    Handle(HandleError),
}

pub(crate) fn create(artifact_bytes: Vec<u8>) -> Result<u64, CreateError> {
    let artifact = verify_artifact(Arc::from(artifact_bytes), ArtifactLimits::default())
        .map_err(|_| CreateError::Verification)?;
    let computer =
        ComputerMachine::start(artifact, profile(), &[], &[]).map_err(|error| match error {
            ComputerStartError::Admission(error) => CreateError::Admission(error),
            ComputerStartError::Start(error) => CreateError::Run(error),
            ComputerStartError::Process(error) => CreateError::Process(error),
        })?;
    sessions()
        .insert(BridgeSession::new(computer, FileSystemLimits::default()))
        .map_err(CreateError::Handle)
}

pub(crate) fn verify(artifact_bytes: &[u8]) -> bool {
    verify_artifact(Arc::from(artifact_bytes), ArtifactLimits::default()).is_ok()
}

pub(crate) fn create_in_store(
    store_handle: u64,
    id: ComputerId,
    rom_bytes: Vec<u8>,
    artifact_bytes: Vec<u8>,
) -> Result<u64, CreateInStoreError> {
    let artifact = verify_artifact(Arc::from(artifact_bytes), ArtifactLimits::default())
        .map_err(|_| CreateInStoreError::Create(CreateError::Verification))?;
    let computer = stores()
        .with(store_handle, |store| {
            let limits = *store.limits();
            let rom = RomImage::admit(Arc::from(rom_bytes), &limits)
                .map(Arc::new)
                .map_err(|_| CreateInStoreError::Rom)?;
            let filesystem = store
                .open_computer(id, rom)
                .map_err(|error| CreateInStoreError::Store(StoreBridgeError::Store(error)))?;
            let initial_capability = FileCapability::new(
                VirtualPath::parse_utf8("/home", &limits)
                    .expect("fixed initial filesystem capability path"),
                FileRights::OWNER,
            );
            ComputerMachine::start_in_filesystem(
                artifact,
                profile(),
                &[],
                &[],
                filesystem,
                initial_capability,
            )
            .map_err(|error| {
                CreateInStoreError::Create(match error {
                    ComputerStartError::Admission(error) => CreateError::Admission(error),
                    ComputerStartError::Start(error) => CreateError::Run(error),
                    ComputerStartError::Process(error) => CreateError::Process(error),
                })
            })
        })
        .map_err(|error| CreateInStoreError::Store(StoreBridgeError::Handle(error)))??;
    let limits = stores()
        .with(store_handle, |store| *store.limits())
        .map_err(|error| CreateInStoreError::Store(StoreBridgeError::Handle(error)))?;
    sessions()
        .insert(BridgeSession::new(computer, limits))
        .map_err(|error| CreateInStoreError::Create(CreateError::Handle(error)))
}

pub(crate) fn create_boot_in_store(
    store_handle: u64,
    id: ComputerId,
    rom_bytes: Vec<u8>,
) -> Result<u64, CreateInStoreError> {
    let computer = stores()
        .with(store_handle, |store| {
            let limits = *store.limits();
            let rom = RomImage::admit(Arc::from(rom_bytes), &limits)
                .map(Arc::new)
                .map_err(|_| CreateInStoreError::Rom)?;
            let filesystem = store
                .open_computer(id, rom)
                .map_err(|error| CreateInStoreError::Store(StoreBridgeError::Store(error)))?;
            let initial_capability = FileCapability::new(
                VirtualPath::parse_utf8("/", &limits)
                    .expect("fixed boot filesystem capability path"),
                FileRights::OWNER,
            );
            ComputerMachine::boot_in_filesystem(
                profile(),
                ProcessLimits::default(),
                &[],
                filesystem,
                initial_capability,
            )
            .map_err(|error| {
                CreateInStoreError::Create(match error {
                    ComputerStartError::Admission(error) => CreateError::Admission(error),
                    ComputerStartError::Start(error) => CreateError::Run(error),
                    ComputerStartError::Process(error) => CreateError::Process(error),
                })
            })
        })
        .map_err(|error| CreateInStoreError::Store(StoreBridgeError::Handle(error)))??;
    let limits = stores()
        .with(store_handle, |store| *store.limits())
        .map_err(|error| CreateInStoreError::Store(StoreBridgeError::Handle(error)))?;
    sessions()
        .insert(BridgeSession::new(computer, limits))
        .map_err(|error| CreateInStoreError::Create(CreateError::Handle(error)))
}

pub(crate) fn advance(
    handle: u64,
    guest_budget: u32,
    maintenance_budget: u32,
) -> Result<OwnedOutcome, BridgeError> {
    sessions()
        .with(handle, |session| {
            let outcome = session
                .computer
                .advance(guest_budget, maintenance_budget)
                .map_err(copy_error)?;
            if let ComputerAdvanceOutcome::CompilationRequested(request) = outcome {
                let token = request.token;
                session.compilation = Some(request);
                Ok(OwnedOutcome::CompilationRequested { token })
            } else {
                Ok(copy_outcome(outcome))
            }
        })
        .map_err(BridgeError::Handle)?
}

pub(crate) fn resume(
    handle: u64,
    request_id: u64,
    response: &OwnedResponse,
) -> Result<(), BridgeError> {
    sessions()
        .with(handle, |session| {
            let borrowed = match response {
                OwnedResponse::SuccessUnit => HostResponse::Success(HostValueInput::Unit),
                OwnedResponse::SuccessString(units) => {
                    HostResponse::Success(HostValueInput::String(units))
                }
                OwnedResponse::Failure(failure) => HostResponse::Failure(*failure),
            };
            session
                .computer
                .resume_host_request(request_id, borrowed)
                .map_err(copy_error)
        })
        .map_err(BridgeError::Handle)?
}

pub(crate) fn terminal_commit(handle: u64) -> Result<(), BridgeError> {
    sessions()
        .with(handle, |session| {
            session.computer.terminal_mut().commit();
        })
        .map_err(BridgeError::Handle)
}

pub(crate) fn filesystem_generation(handle: u64) -> Result<u64, BridgeError> {
    sessions()
        .with(handle, |session| session.computer.filesystem_generation())
        .map_err(BridgeError::Handle)
}

pub(crate) fn verify_for_deploy(
    handle: u64,
    artifact: Arc<[u8]>,
) -> Result<u64, DeploymentVerifyBridgeError> {
    let candidate = sessions()
        .with(handle, |session| {
            session.computer.verify_for_deploy(artifact)
        })
        .map_err(DeploymentVerifyBridgeError::Session)?
        .map_err(|error| match error {
            HostVerifyError::Artifact(_) => DeploymentVerifyBridgeError::Verification,
            HostVerifyError::Admission(_) => DeploymentVerifyBridgeError::Admission,
        })?;
    deployment_candidates()
        .insert(Box::new(candidate))
        .map_err(DeploymentVerifyBridgeError::Candidate)
}

pub(crate) fn executable_revision(
    handle: u64,
    path: &str,
) -> Result<ExecutableRevision, RevisionBridgeError> {
    sessions()
        .with(handle, |session| {
            let path = VirtualPath::parse_utf8(path, &session.filesystem_limits)
                .map_err(RevisionBridgeError::FileSystem)?;
            session
                .computer
                .executable_revision(&path)
                .map_err(RevisionBridgeError::FileSystem)
        })
        .map_err(RevisionBridgeError::Session)?
}

pub(crate) fn deploy(
    handle: u64,
    candidate_handle: u64,
    path: &str,
    expected: ExecutableRevision,
) -> Result<ExecutableRevision, DeploymentBridgeError> {
    sessions()
        .with(handle, |session| {
            let path = VirtualPath::parse_utf8(path, &session.filesystem_limits)
                .map_err(DeploymentBridgeError::FileSystem)?;
            deployment_candidates()
                .consume_if(candidate_handle, |candidate| {
                    match session.computer.deploy(&path, expected, *candidate) {
                        Ok(revision) => Ok(revision),
                        Err(failure) => {
                            let error = match failure.error() {
                                HostDeployError::WrongMachine => {
                                    DeploymentBridgeError::WrongMachine
                                }
                                HostDeployError::ProfileChanged => {
                                    DeploymentBridgeError::ProfileChanged
                                }
                                HostDeployError::FileSystem(error) => {
                                    DeploymentBridgeError::FileSystem(error)
                                }
                            };
                            Err((error, Box::new(failure.into_candidate())))
                        }
                    }
                })
                .map_err(DeploymentBridgeError::Candidate)?
        })
        .map_err(DeploymentBridgeError::Session)?
}

pub(crate) fn close_deployment_candidate(handle: u64) -> Result<(), HandleError> {
    deployment_candidates().close(handle)
}

pub(crate) fn submit_canonical_line(
    handle: u64,
    line: &[u16],
) -> Result<(), Result<HandleError, CanonicalLineSubmissionError>> {
    sessions()
        .with(handle, |session| {
            session.computer.submit_canonical_line(line)
        })
        .map_err(Ok)?
        .map_err(Err)
}

pub(crate) fn with_terminal<R>(
    handle: u64,
    action: impl FnOnce(&TerminalDevice) -> R,
) -> Result<R, BridgeError> {
    sessions()
        .with(handle, |session| action(session.computer.terminal()))
        .map_err(BridgeError::Handle)
}

pub(crate) fn terminal_changes_since(
    handle: u64,
    revision: u64,
) -> Result<TerminalUpdate, BridgeError> {
    sessions()
        .with(handle, |session| {
            session.computer.terminal().changes_since(revision)
        })
        .map_err(BridgeError::Handle)
}

pub(crate) fn terminal_key(
    handle: u64,
    key: u16,
    action: u32,
    modifiers: u32,
) -> Result<(), BridgeError> {
    let key = TerminalKey::try_from(key).map_err(|_| BridgeError::InvalidOperation)?;
    let action = match action {
        0 => TerminalKeyAction::Press,
        1 => TerminalKeyAction::Repeat,
        _ => return Err(BridgeError::InvalidOperation),
    };
    let modifiers = u8::try_from(modifiers)
        .ok()
        .and_then(|value| TerminalModifiers::new(value).ok())
        .ok_or(BridgeError::InvalidOperation)?;
    sessions()
        .with(handle, |session| {
            session
                .computer
                .terminal_mut()
                .push_key(TerminalKeyEvent::new(key, action, modifiers))
        })
        .map_err(BridgeError::Handle)?
        .map_err(copy_input_error)
}

pub(crate) fn terminal_text(handle: u64, text: &str) -> Result<(), BridgeError> {
    sessions()
        .with(handle, |session| {
            session.computer.terminal_mut().push_text(text)
        })
        .map_err(BridgeError::Handle)?
        .map_err(copy_input_error)
}

pub(crate) fn close(handle: u64) -> Result<(), BridgeError> {
    sessions().close(handle).map_err(BridgeError::Handle)
}

pub(crate) fn store_open(root: PathBuf, limits: FileSystemLimits) -> Result<u64, StoreCreateError> {
    let store = WorldFileSystemStore::open(&root, limits).map_err(StoreCreateError::Open)?;
    stores().insert(store).map_err(StoreCreateError::Handle)
}

pub(crate) fn store_health(handle: u64) -> Result<StoreHealth, StoreBridgeError> {
    stores()
        .with(handle, |store| store.health())
        .map_err(StoreBridgeError::Handle)
}

pub(crate) fn store_durable_generation(
    handle: u64,
    id: ComputerId,
) -> Result<u64, StoreBridgeError> {
    stores()
        .with(handle, |store| store.durable_generation(id))
        .map_err(StoreBridgeError::Handle)?
        .map_err(StoreBridgeError::Store)
}

pub(crate) fn store_flush(
    handle: u64,
    id: ComputerId,
    generation: u64,
) -> Result<(), StoreBridgeError> {
    stores()
        .with(handle, |store| store.flush(id, generation))
        .map_err(StoreBridgeError::Handle)?
        .map_err(StoreBridgeError::Store)
}

pub(crate) fn store_tombstone(handle: u64, id: ComputerId) -> Result<(), StoreBridgeError> {
    stores()
        .with(handle, |store| store.tombstone(id))
        .map_err(StoreBridgeError::Handle)?
        .map_err(StoreBridgeError::Store)
}

pub(crate) fn store_recover(handle: u64, id: ComputerId) -> Result<(), StoreBridgeError> {
    stores()
        .with(handle, |store| store.recover_tombstone(id))
        .map_err(StoreBridgeError::Handle)?
        .map_err(StoreBridgeError::Store)
}

pub(crate) fn store_close(handle: u64) -> Result<(), StoreBridgeError> {
    stores()
        .with(handle, |store| store.close())
        .map_err(StoreBridgeError::Handle)?
        .map_err(StoreBridgeError::Store)?;
    stores().close(handle).map_err(StoreBridgeError::Handle)
}

pub(crate) fn compilation_request_size(handle: u64, token: u64) -> Result<usize, BridgeError> {
    sessions()
        .with(handle, |session| {
            let request = pending_compilation(session, token)?;
            crate::wire::compilation_request_size(request).ok_or(BridgeError::InvalidOperation)
        })
        .map_err(BridgeError::Handle)?
}

pub(crate) fn copy_compilation_request(
    handle: u64,
    token: u64,
    output: &mut [u8],
) -> Result<usize, BridgeError> {
    sessions()
        .with(handle, |session| {
            let request = pending_compilation(session, token)?;
            crate::wire::encode_compilation_request_into(output, request)
                .map_err(|_| BridgeError::InvalidOperation)
        })
        .map_err(BridgeError::Handle)?
}

pub(crate) fn complete_compilation_artifact(
    handle: u64,
    token: u64,
    artifact: &[u8],
) -> Result<(), BridgeError> {
    sessions()
        .with(handle, |session| {
            pending_compilation(session, token)?;
            session
                .computer
                .complete_compilation_success(token, artifact)
                .map_err(copy_error)?;
            session.compilation = None;
            Ok(())
        })
        .map_err(BridgeError::Handle)?
}

pub(crate) fn complete_compilation_failure(
    handle: u64,
    token: u64,
    diagnostics: &str,
) -> Result<(), BridgeError> {
    sessions()
        .with(handle, |session| {
            pending_compilation(session, token)?;
            session
                .computer
                .complete_compilation_failure(token, diagnostics)
                .map_err(copy_error)?;
            session.compilation = None;
            Ok(())
        })
        .map_err(BridgeError::Handle)?
}

fn pending_compilation(
    session: &BridgeSession,
    token: u64,
) -> Result<&CompilationRequest, BridgeError> {
    session
        .compilation
        .as_ref()
        .filter(|request| request.token == token)
        .ok_or(BridgeError::InvalidOperation)
}

fn sessions() -> &'static HandleTable<BridgeSession> {
    SESSIONS.get_or_init(HandleTable::default)
}

fn stores() -> &'static HandleTable<Arc<WorldFileSystemStore>> {
    STORES.get_or_init(HandleTable::default)
}

fn deployment_candidates() -> &'static HandleTable<Box<DeploymentCandidate>> {
    DEPLOYMENT_CANDIDATES.get_or_init(HandleTable::default)
}

fn copy_outcome(outcome: ComputerAdvanceOutcome) -> OwnedOutcome {
    match outcome {
        ComputerAdvanceOutcome::SliceExhausted => OwnedOutcome::SliceExhausted,
        ComputerAdvanceOutcome::WaitingForTerminalEvent => OwnedOutcome::WaitingForTerminalEvent,
        ComputerAdvanceOutcome::HostRequest(request) => OwnedOutcome::HostRequest(OwnedRequest {
            id: request.id,
            namespace: request.namespace.into(),
            name: request.name.into(),
            abi_major: request.abi_major,
            abi_minor: request.abi_minor,
            operation: request.operation,
            arguments: request
                .arguments
                .into_vec()
                .into_iter()
                .map(copy_value)
                .collect(),
        }),
        ComputerAdvanceOutcome::AllocationExhausted(value) => {
            OwnedOutcome::AllocationExhausted(value)
        }
        ComputerAdvanceOutcome::QuotaExhausted(value) => OwnedOutcome::QuotaExhausted(value),
        ComputerAdvanceOutcome::Halted(value) => OwnedOutcome::Halted(value.map(copy_value)),
        ComputerAdvanceOutcome::Crashed(value) => OwnedOutcome::Crashed(value),
        ComputerAdvanceOutcome::Faulted(value) => OwnedOutcome::Faulted(value),
        ComputerAdvanceOutcome::HostFailed(value) => OwnedOutcome::HostFailed(value),
        ComputerAdvanceOutcome::CompilationRequested(_) => {
            unreachable!("compilation requests are retained by the bridge session")
        }
    }
}

fn copy_value(value: ComputerValue) -> OwnedValue {
    match value {
        ComputerValue::I32(value) => OwnedValue::I32(value),
        ComputerValue::I64(value) => OwnedValue::I64(value),
        ComputerValue::F32(value) => OwnedValue::F32(value),
        ComputerValue::F64(value) => OwnedValue::F64(value),
        ComputerValue::Bool(value) => OwnedValue::Bool(value),
        ComputerValue::Char(value) => OwnedValue::Char(value),
        ComputerValue::String(value) => OwnedValue::String(value.into_vec()),
    }
}

fn copy_error(error: ComputerError) -> BridgeError {
    match error {
        ComputerError::Run(error) => BridgeError::Run(error),
        ComputerError::Resume(error) => BridgeError::Resume(error),
        ComputerError::InvalidRequestId => BridgeError::InvalidRequestId,
        ComputerError::InvalidTerminalRequest
        | ComputerError::InvalidStdioRequest
        | ComputerError::InvalidFileSystemRequest
        | ComputerError::InvalidProcessRequest
        | ComputerError::InvalidCompilerRequest
        | ComputerError::ActiveCompilation
        | ComputerError::NoActiveCompilation
        | ComputerError::InvalidCompilationToken
        | ComputerError::ActiveTerminalEvent
        | ComputerError::NoActiveTerminalEvent
        | ComputerError::WrongTerminalEventKind
        | ComputerError::TerminalInputBusy(_) => BridgeError::InvalidOperation,
    }
}

fn copy_input_error(_error: TerminalInputError) -> BridgeError {
    BridgeError::InvalidOperation
}

fn profile() -> ExecutionProfile {
    ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 64,
        maximum_host_requests: 64,
        maximum_events: 64,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        standard_library_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: EntryArgumentLimits {
            maximum_count: 64,
            maximum_code_units_per_argument: 4096,
            maximum_total_code_units: 16_384,
        },
    }
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicU64, Ordering};

    use compukter_vm::{FileCapability, FileRights, FileSystemError, RomImage, VirtualPath};
    use sha2::{Digest, Sha256};

    use super::*;

    #[test]
    fn stdio_and_input_ownership_errors_are_invalid_bridge_operations() {
        assert_eq!(
            BridgeError::InvalidOperation,
            copy_error(ComputerError::InvalidStdioRequest),
        );
        assert_eq!(
            BridgeError::InvalidOperation,
            copy_error(ComputerError::TerminalInputBusy(
                compukter_vm::InputOwnershipError::CanonicalBusy,
            )),
        );
    }

    #[test]
    fn closing_a_store_faults_an_existing_computer_lease_closed() {
        static NEXT: AtomicU64 = AtomicU64::new(1);
        let root = std::env::temp_dir()
            .join("compukters-ffi-bridge-tests")
            .join(format!(
                "{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
        std::fs::create_dir_all(&root).unwrap();
        let root = root.canonicalize().unwrap();
        let limits = FileSystemLimits::testing();
        let handle = store_open(root.clone(), limits).unwrap();
        let mut filesystem = stores()
            .with(handle, |store| {
                store.open_computer(
                    ComputerId::from_bytes([1; 16]),
                    Arc::new(empty_rom(&limits)),
                )
            })
            .unwrap()
            .unwrap();

        store_close(handle).unwrap();

        let home = VirtualPath::parse_utf8("/home", &limits).unwrap();
        let child = VirtualPath::parse_utf8("/home/after-close", &limits).unwrap();
        let owner = FileCapability::new(home, FileRights::OWNER);
        assert_eq!(
            Err(FileSystemError::Closed),
            filesystem.create_directory(&owner, &child)
        );
        std::fs::remove_dir_all(root).unwrap();
    }

    fn empty_rom(limits: &FileSystemLimits) -> RomImage {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(b"CPKTROM\0");
        bytes.extend_from_slice(&1_u16.to_le_bytes());
        bytes.extend_from_slice(&0_u16.to_le_bytes());
        bytes.extend_from_slice(&0_u32.to_le_bytes());
        let digest = Sha256::digest(&bytes);
        bytes.extend_from_slice(&digest);
        RomImage::admit(bytes.into(), limits).unwrap()
    }
}
