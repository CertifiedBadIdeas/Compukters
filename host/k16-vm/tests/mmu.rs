use k16_vm::mmu::{
    MmuAccess, MmuAddressSpace, MmuAddressSpaces, MmuFault, MmuFaultKind, MmuMapFlags,
    MmuPrivilege, K16_VM_PAGE_SIZE,
};

#[test]
fn mmu_maps_contiguous_virtual_pages_to_physical_pages() {
    assert_eq!(K16_VM_PAGE_SIZE, 4096);

    let mut space = MmuAddressSpace::new(148 * 1024);
    space
        .map_pages(
            0x0001_0000,
            0x0000_4000,
            1,
            MmuMapFlags::USER_ACCESSIBLE | MmuMapFlags::EXECUTABLE,
        )
        .unwrap();
    space
        .map_pages(
            0x0001_1000,
            0x0000_5000,
            1,
            MmuMapFlags::USER_ACCESSIBLE | MmuMapFlags::WRITABLE,
        )
        .unwrap();

    assert_eq!(
        space
            .translate(0x0001_0123, MmuAccess::Fetch, MmuPrivilege::User)
            .unwrap(),
        0x0000_4123
    );
    assert_eq!(
        space
            .translate(0x0001_1ffc, MmuAccess::Load, MmuPrivilege::User)
            .unwrap(),
        0x0000_5ffc
    );
    assert_eq!(
        space
            .translate(0x0001_1000, MmuAccess::Store, MmuPrivilege::User)
            .unwrap(),
        0x0000_5000
    );
}

#[test]
fn mmu_privilege_distinguishes_kernel_supervisor_pages_from_user_pages() {
    let mut space = MmuAddressSpace::new(148 * 1024);
    space
        .map_pages(0x0001_0000, 0x0000_4000, 1, MmuMapFlags::EXECUTABLE)
        .unwrap();

    assert_eq!(
        space
            .translate(0x0001_0000, MmuAccess::Fetch, MmuPrivilege::Kernel)
            .unwrap(),
        0x0000_4000
    );
    assert_eq!(
        space.translate(0x0001_0000, MmuAccess::Fetch, MmuPrivilege::User),
        Err(MmuFault {
            address: 0x0001_0000,
            access: MmuAccess::Fetch,
            kind: MmuFaultKind::Permission,
        })
    );
}

#[test]
fn mmu_rejects_unmapped_and_permission_denied_accesses() {
    let mut space = MmuAddressSpace::new(148 * 1024);
    space
        .map_pages(0x0001_0000, 0x0000_4000, 1, MmuMapFlags::USER_ACCESSIBLE)
        .unwrap();

    assert_eq!(
        space.translate(0x0002_0000, MmuAccess::Load, MmuPrivilege::User),
        Err(MmuFault {
            address: 0x0002_0000,
            access: MmuAccess::Load,
            kind: MmuFaultKind::NotPresent,
        })
    );
    assert_eq!(
        space.translate(0x0001_0000, MmuAccess::Store, MmuPrivilege::User),
        Err(MmuFault {
            address: 0x0001_0000,
            access: MmuAccess::Store,
            kind: MmuFaultKind::Permission,
        })
    );
    assert_eq!(
        space.translate(0x0001_0000, MmuAccess::Fetch, MmuPrivilege::User),
        Err(MmuFault {
            address: 0x0001_0000,
            access: MmuAccess::Fetch,
            kind: MmuFaultKind::Permission,
        })
    );
}

#[test]
fn mmu_rejects_invalid_mappings() {
    let mut space = MmuAddressSpace::new(148 * 1024);
    let flags = MmuMapFlags::USER_ACCESSIBLE;

    assert_invalid_mapping(space.map_pages(0x0001_0001, 0x0000_4000, 1, flags));
    assert_invalid_mapping(space.map_pages(0x0001_0000, 0x0000_4001, 1, flags));
    assert_invalid_mapping(space.map_pages(0x0001_0000, 0x0000_4000, 0, flags));
    assert_invalid_mapping(space.map_pages(0x0001_0000, 148 * 1024, 1, flags));
    assert_invalid_mapping(space.map_pages(0xffff_f000, 0x0000_4000, 2, flags));

    space.map_pages(0x0001_0000, 0x0000_4000, 2, flags).unwrap();
    assert_invalid_mapping(space.map_pages(0x0001_1000, 0x0000_8000, 1, flags));
}

#[test]
fn mmu_rejects_user_accessible_writable_executable_mappings() {
    let mut space = MmuAddressSpace::new(148 * 1024);
    let flags = MmuMapFlags::USER_ACCESSIBLE | MmuMapFlags::WRITABLE | MmuMapFlags::EXECUTABLE;

    assert_invalid_mapping(space.map_pages(0x0001_0000, 0x0000_4000, 1, flags));
}

#[test]
fn mmu_rejects_protecting_user_mapping_to_writable_executable() {
    let mut space = MmuAddressSpace::new(148 * 1024);
    space
        .map_pages(0x0001_0000, 0x0000_4000, 1, MmuMapFlags::USER_ACCESSIBLE)
        .unwrap();
    let flags = MmuMapFlags::USER_ACCESSIBLE | MmuMapFlags::WRITABLE | MmuMapFlags::EXECUTABLE;

    assert_invalid_mapping(space.protect_pages(0x0001_0000, 1, flags));
}

fn assert_invalid_mapping(result: Result<(), MmuFault>) {
    assert!(matches!(
        result,
        Err(MmuFault {
            kind: MmuFaultKind::InvalidMapping,
            ..
        })
    ));
}

#[test]
fn mmu_protects_and_unmaps_existing_pages() {
    let mut space = MmuAddressSpace::new(148 * 1024);
    space
        .map_pages(
            0x0001_0000,
            0x0000_4000,
            2,
            MmuMapFlags::USER_ACCESSIBLE | MmuMapFlags::WRITABLE,
        )
        .unwrap();

    space
        .protect_pages(
            0x0001_0000,
            2,
            MmuMapFlags::USER_ACCESSIBLE | MmuMapFlags::EXECUTABLE,
        )
        .unwrap();

    assert_eq!(
        space
            .translate(0x0001_0000, MmuAccess::Fetch, MmuPrivilege::User)
            .unwrap(),
        0x0000_4000
    );
    assert_eq!(
        space.translate(0x0001_0000, MmuAccess::Store, MmuPrivilege::User),
        Err(MmuFault {
            address: 0x0001_0000,
            access: MmuAccess::Store,
            kind: MmuFaultKind::Permission,
        })
    );

    space.unmap_pages(0x0001_0000, 2).unwrap();
    assert_eq!(
        space.translate(0x0001_0000, MmuAccess::Load, MmuPrivilege::User),
        Err(MmuFault {
            address: 0x0001_0000,
            access: MmuAccess::Load,
            kind: MmuFaultKind::NotPresent,
        })
    );
}

#[test]
fn mmu_protects_and_unmaps_subranges_inside_existing_extent() {
    let mut space = MmuAddressSpace::new(148 * 1024);
    space
        .map_pages(
            0x0001_0000,
            0x0000_4000,
            3,
            MmuMapFlags::USER_ACCESSIBLE | MmuMapFlags::WRITABLE,
        )
        .unwrap();

    space
        .protect_pages(0x0001_1000, 1, MmuMapFlags::USER_ACCESSIBLE)
        .unwrap();

    assert!(space
        .translate(0x0001_0000, MmuAccess::Store, MmuPrivilege::User)
        .is_ok());
    assert_eq!(
        space.translate(0x0001_1000, MmuAccess::Store, MmuPrivilege::User),
        Err(MmuFault {
            address: 0x0001_1000,
            access: MmuAccess::Store,
            kind: MmuFaultKind::Permission,
        })
    );
    assert!(space
        .translate(0x0001_2000, MmuAccess::Store, MmuPrivilege::User)
        .is_ok());

    space.unmap_pages(0x0001_1000, 1).unwrap();

    assert_eq!(
        space.translate(0x0001_1000, MmuAccess::Load, MmuPrivilege::User),
        Err(MmuFault {
            address: 0x0001_1000,
            access: MmuAccess::Load,
            kind: MmuFaultKind::NotPresent,
        })
    );
    assert_eq!(
        space
            .translate(0x0001_2000, MmuAccess::Load, MmuPrivilege::User)
            .unwrap(),
        0x0000_6000
    );
}

#[test]
fn mmu_address_space_table_creates_and_destroys_spaces() {
    let mut spaces = MmuAddressSpaces::new();
    let first = spaces.create(148 * 1024).unwrap();
    let second = spaces.create(148 * 1024).unwrap();

    assert_ne!(first, second);
    assert!(spaces.get(first).is_some());
    assert!(spaces.get(second).is_some());
    assert!(spaces.destroy(first));
    assert!(spaces.get(first).is_none());
    assert!(spaces.get(second).is_some());
    assert!(!spaces.destroy(first));
}
