use std::collections::{BTreeMap, BTreeSet};
use std::path::Path;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LinkMap {
    pub program: String,
    pub target: String,
    pub payload_bytes: u64,
    pub memory_bytes: u64,
    pub sections: Vec<LinkMapSection>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LinkMapSection {
    pub class: String,
    pub file_bytes: u64,
    pub memory_bytes: u64,
    pub contributor: String,
    pub section: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SizeReport {
    pub programs: Vec<ProgramSummary>,
    pub duplicates: Vec<DuplicateSummary>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ProgramSummary {
    pub program: String,
    pub payload_bytes: u64,
    pub memory_bytes: u64,
    pub retained_sections: usize,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DuplicateSummary {
    pub class: String,
    pub contributor: String,
    pub section: String,
    pub program_count: usize,
    pub total_file_bytes: u64,
    pub total_memory_bytes: u64,
}

pub fn parse_link_map(program: &str, text: &str) -> Result<LinkMap, String> {
    let mut lines = text.lines();
    let header = lines
        .next()
        .ok_or_else(|| format!("{program}: missing K16 link map header"))?;
    let header_fields = parse_fields(header)?;
    let target = header_fields
        .get("target")
        .ok_or_else(|| format!("{program}: missing target in map header"))?
        .to_string();
    let payload_bytes = parse_u64_field(program, "payload_bytes", &header_fields)?;
    let memory_bytes = parse_u64_field(program, "memory_bytes", &header_fields)?;
    let retained_sections = parse_usize_field(program, "retained_sections", &header_fields)?;

    let mut sections = Vec::new();
    for (index, line) in lines.enumerate() {
        if line.trim().is_empty() {
            continue;
        }
        if !line.starts_with("section ") {
            return Err(format!(
                "{program}: unexpected line {} in map: {line}",
                index + 2
            ));
        }
        let fields = parse_fields(line)?;
        let class = fields
            .get("class")
            .ok_or_else(|| format!("{program}: section line {} is missing class", index + 2))?
            .to_string();
        let file_bytes = parse_u64_field(program, "file_bytes", &fields)?;
        let memory_bytes = parse_u64_field(program, "memory_bytes", &fields)?;
        let object = fields
            .get("object")
            .ok_or_else(|| format!("{program}: section line {} is missing object", index + 2))?;
        let section = fields
            .get("name")
            .ok_or_else(|| format!("{program}: section line {} is missing name", index + 2))?
            .to_string();
        sections.push(LinkMapSection {
            class,
            file_bytes,
            memory_bytes,
            contributor: normalize_contributor(object),
            section,
        });
    }

    if sections.len() != retained_sections {
        return Err(format!(
            "{program}: retained_sections header says {retained_sections}, parsed {}",
            sections.len()
        ));
    }

    Ok(LinkMap {
        program: program.to_string(),
        target,
        payload_bytes,
        memory_bytes,
        sections,
    })
}

pub fn build_size_report(maps: Vec<LinkMap>) -> SizeReport {
    let programs = maps
        .iter()
        .map(|map| ProgramSummary {
            program: map.program.clone(),
            payload_bytes: map.payload_bytes,
            memory_bytes: map.memory_bytes,
            retained_sections: map.sections.len(),
        })
        .collect();
    let mut duplicate_entries: BTreeMap<(String, String, String), DuplicateAccumulator> =
        BTreeMap::new();

    for map in &maps {
        for section in &map.sections {
            let entry = duplicate_entries
                .entry((
                    section.class.clone(),
                    section.contributor.clone(),
                    section.section.clone(),
                ))
                .or_default();
            entry.programs.insert(map.program.clone());
            entry.total_file_bytes += section.file_bytes;
            entry.total_memory_bytes += section.memory_bytes;
        }
    }

    let mut duplicates: Vec<_> = duplicate_entries
        .into_iter()
        .filter_map(|((class, contributor, section), entry)| {
            let program_count = entry.programs.len();
            (program_count > 1).then_some(DuplicateSummary {
                class,
                contributor,
                section,
                program_count,
                total_file_bytes: entry.total_file_bytes,
                total_memory_bytes: entry.total_memory_bytes,
            })
        })
        .collect();
    duplicates.sort_by(|left, right| {
        right
            .total_file_bytes
            .cmp(&left.total_file_bytes)
            .then_with(|| left.contributor.cmp(&right.contributor))
            .then_with(|| left.section.cmp(&right.section))
            .then_with(|| left.class.cmp(&right.class))
    });

    SizeReport {
        programs,
        duplicates,
    }
}

pub fn format_size_report(report: &SizeReport) -> String {
    let total_payload_bytes: u64 = report
        .programs
        .iter()
        .map(|program| program.payload_bytes)
        .sum();
    let total_memory_bytes: u64 = report
        .programs
        .iter()
        .map(|program| program.memory_bytes)
        .sum();
    let mut output = format!(
        "K16 userland size report programs={} total_payload_bytes={} total_memory_bytes={}\n\n",
        report.programs.len(),
        total_payload_bytes,
        total_memory_bytes
    );
    output.push_str("program payload_bytes memory_bytes retained_sections name\n");
    for program in &report.programs {
        output.push_str(&format!(
            "{} {} {} {}\n",
            program.payload_bytes, program.memory_bytes, program.retained_sections, program.program
        ));
    }
    output.push('\n');
    output.push_str("duplicate_file_bytes program_count class contributor section\n");
    for duplicate in &report.duplicates {
        output.push_str(&format!(
            "{} {} {} {} {}\n",
            duplicate.total_file_bytes,
            duplicate.program_count,
            duplicate.class,
            duplicate.contributor,
            duplicate.section
        ));
    }
    output
}

#[derive(Default)]
struct DuplicateAccumulator {
    programs: BTreeSet<String>,
    total_file_bytes: u64,
    total_memory_bytes: u64,
}

fn parse_fields(line: &str) -> Result<BTreeMap<&str, &str>, String> {
    let mut fields = BTreeMap::new();
    for token in line.split_ascii_whitespace() {
        let Some((key, value)) = token.split_once('=') else {
            continue;
        };
        fields.insert(key, value);
    }
    Ok(fields)
}

fn parse_u64_field(
    program: &str,
    field: &str,
    fields: &BTreeMap<&str, &str>,
) -> Result<u64, String> {
    fields
        .get(field)
        .ok_or_else(|| format!("{program}: missing {field}"))?
        .parse()
        .map_err(|_| format!("{program}: invalid {field}"))
}

fn parse_usize_field(
    program: &str,
    field: &str,
    fields: &BTreeMap<&str, &str>,
) -> Result<usize, String> {
    fields
        .get(field)
        .ok_or_else(|| format!("{program}: missing {field}"))?
        .parse()
        .map_err(|_| format!("{program}: invalid {field}"))
}

fn normalize_contributor(object: &str) -> String {
    if let Some((_, dep_object)) = object.rsplit_once("/deps/") {
        return normalize_rust_archive(dep_object);
    }
    Path::new(object)
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or(object)
        .to_string()
}

fn normalize_rust_archive(dep_object: &str) -> String {
    let Some(archive_end) = dep_object.find(".rlib").map(|index| index + ".rlib".len()) else {
        return dep_object.to_string();
    };
    let archive = &dep_object[..archive_end];
    let suffix = &dep_object[archive_end..];
    let stem = &archive[..archive.len() - ".rlib".len()];
    let Some(hash_separator) = stem.rfind('-') else {
        return dep_object.to_string();
    };
    let hash = &stem[hash_separator + 1..];
    if hash.is_empty() || !hash.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return dep_object.to_string();
    }
    format!("{}.rlib{}", &stem[..hash_separator], suffix)
}

#[cfg(test)]
mod tests {
    use super::{build_size_report, format_size_report, parse_link_map};

    const CAT_MAP: &str = "\
K16 link map target=program-dynamic load_addr=0x00000000 payload_bytes=120 memory_bytes=132 retained_sections=3
section offset=0x00000000 class=text file_bytes=40 memory_bytes=40 object=/tmp/cat/k16-unknown-kraftos/release/deps/k16_cat-aaaa.rcgu.o name=.text.k16.main
section offset=0x00000028 class=text file_bytes=34 memory_bytes=34 object=/tmp/cat/k16-startup.o name=.text.k16
section offset=0x0000004a class=text file_bytes=46 memory_bytes=58 object=/tmp/cat/k16-unknown-kraftos/release/deps/libcore-deadbeef.rlib(/7) name=.text.k16.core_fmt
";

    const LS_MAP: &str = "\
K16 link map target=program-dynamic load_addr=0x00000000 payload_bytes=110 memory_bytes=110 retained_sections=3
section offset=0x00000000 class=text file_bytes=30 memory_bytes=30 object=/tmp/ls/k16-unknown-kraftos/release/deps/k16_ls-bbbb.rcgu.o name=.text.k16.main
section offset=0x0000001e class=text file_bytes=34 memory_bytes=34 object=/tmp/ls/k16-startup.o name=.text.k16
section offset=0x00000040 class=text file_bytes=46 memory_bytes=46 object=/tmp/ls/k16-unknown-kraftos/release/deps/libcore-feedface.rlib(/7) name=.text.k16.core_fmt
";

    #[test]
    fn link_map_parser_reads_header_and_sections() {
        let map = parse_link_map("cat", CAT_MAP).expect("map parses");

        assert_eq!(map.program, "cat");
        assert_eq!(map.target, "program-dynamic");
        assert_eq!(map.payload_bytes, 120);
        assert_eq!(map.memory_bytes, 132);
        assert_eq!(map.sections.len(), 3);
        assert_eq!(map.sections[1].class, "text");
        assert_eq!(map.sections[1].file_bytes, 34);
        assert_eq!(map.sections[1].memory_bytes, 34);
        assert_eq!(map.sections[1].contributor, "k16-startup.o");
        assert_eq!(map.sections[2].contributor, "libcore.rlib(/7)");
    }

    #[test]
    fn size_report_aggregates_repeated_contributors_across_programs() {
        let cat = parse_link_map("cat", CAT_MAP).expect("cat map parses");
        let ls = parse_link_map("ls", LS_MAP).expect("ls map parses");
        let report = build_size_report(vec![cat, ls]);

        assert_eq!(report.programs.len(), 2);
        assert_eq!(report.programs[0].program, "cat");
        assert_eq!(report.programs[0].payload_bytes, 120);
        assert_eq!(report.programs[1].program, "ls");
        assert_eq!(report.duplicates.len(), 2);
        assert_eq!(report.duplicates[0].contributor, "libcore.rlib(/7)");
        assert_eq!(report.duplicates[0].section, ".text.k16.core_fmt");
        assert_eq!(report.duplicates[0].program_count, 2);
        assert_eq!(report.duplicates[0].total_file_bytes, 92);
        assert_eq!(report.duplicates[1].contributor, "k16-startup.o");
        assert_eq!(report.duplicates[1].total_file_bytes, 68);
    }

    #[test]
    fn size_report_formatter_prints_stable_summary() {
        let cat = parse_link_map("cat", CAT_MAP).expect("cat map parses");
        let ls = parse_link_map("ls", LS_MAP).expect("ls map parses");
        let report = build_size_report(vec![cat, ls]);

        assert_eq!(
            format_size_report(&report),
            "\
K16 userland size report programs=2 total_payload_bytes=230 total_memory_bytes=242

program payload_bytes memory_bytes retained_sections name
120 132 3 cat
110 110 3 ls

duplicate_file_bytes program_count class contributor section
92 2 text libcore.rlib(/7) .text.k16.core_fmt
68 2 text k16-startup.o .text.k16
"
        );
    }
}
