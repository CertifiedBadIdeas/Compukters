use std::fs;
use std::path::{Component, Path, PathBuf};

#[derive(Debug, Clone)]
pub struct DeviceFilesystem {
    root: PathBuf,
    quota_bytes: u64,
}

#[derive(Debug, Clone)]
struct WorkspaceEntry {
    name: String,
    directory: bool,
}

impl DeviceFilesystem {
    pub fn attach(root: impl Into<PathBuf>, quota_bytes: i64) -> Result<Self, String> {
        if quota_bytes < 0 {
            return Err(format!(
                "native filesystem quota must be non-negative but was {quota_bytes}"
            ));
        }
        let root = root.into();
        fs::create_dir_all(&root).map_err(|error| {
            format!(
                "Cannot create native filesystem root `{}`: {error}",
                root.display()
            )
        })?;
        let root = root.canonicalize().map_err(|error| {
            format!(
                "Cannot canonicalize native filesystem root `{}`: {error}",
                root.display()
            )
        })?;
        Ok(Self {
            root,
            quota_bytes: quota_bytes as u64,
        })
    }

    pub fn exists(&self, working_directory: &str, path: &str) -> Result<bool, String> {
        Ok(self.resolve(working_directory, path)?.exists())
    }

    pub fn is_directory(&self, working_directory: &str, path: &str) -> Result<bool, String> {
        Ok(self.resolve(working_directory, path)?.is_dir())
    }

    pub fn read_text(&self, working_directory: &str, path: &str) -> Result<String, String> {
        let target = self.resolve(working_directory, path)?;
        if !target.exists() || target.is_dir() {
            return Ok(String::new());
        }
        fs::read_to_string(&target).map_err(|error| {
            format!(
                "Cannot read native filesystem file `{}`: {error}",
                target.display()
            )
        })
    }

    pub fn write_text(
        &self,
        working_directory: &str,
        path: &str,
        text: &str,
    ) -> Result<(), String> {
        let target = self.resolve(working_directory, path)?;
        self.ensure_within_quota(&target, text.as_bytes().len() as u64)?;
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent).map_err(|error| {
                format!(
                    "Cannot create native filesystem parent `{}`: {error}",
                    parent.display()
                )
            })?;
        }
        fs::write(&target, text).map_err(|error| {
            format!(
                "Cannot write native filesystem file `{}`: {error}",
                target.display()
            )
        })
    }

    pub fn make_dir(&self, working_directory: &str, path: &str) -> Result<bool, String> {
        let target = self.resolve(working_directory, path)?;
        if target.exists() {
            return Ok(target.is_dir());
        }
        fs::create_dir_all(&target).map_err(|error| {
            format!(
                "Cannot create native filesystem directory `{}`: {error}",
                target.display()
            )
        })?;
        Ok(true)
    }

    pub fn remove(&self, working_directory: &str, path: &str) -> Result<bool, String> {
        let target = self.resolve(working_directory, path)?;
        if !target.exists() {
            return Ok(false);
        }
        if target.is_dir() {
            fs::remove_dir(&target).map_err(|error| {
                format!(
                    "Cannot remove native filesystem directory `{}`: {error}",
                    target.display()
                )
            })?;
        } else {
            fs::remove_file(&target).map_err(|error| {
                format!(
                    "Cannot remove native filesystem file `{}`: {error}",
                    target.display()
                )
            })?;
        }
        Ok(true)
    }

    pub fn list(&self, working_directory: &str, path: &str) -> Result<String, String> {
        let target = self.resolve(working_directory, path)?;
        if !target.exists() {
            return Ok(String::new());
        }
        if !target.is_dir() {
            return Ok(self.format_entries(vec![self.entry_for(&target)?]));
        }
        let mut entries = Vec::new();
        for entry in fs::read_dir(&target).map_err(|error| {
            format!(
                "Cannot list native filesystem directory `{}`: {error}",
                target.display()
            )
        })? {
            entries.push(self.entry_for(&entry.map_err(|error| error.to_string())?.path())?);
        }
        entries.sort_by(|left, right| left.name.cmp(&right.name));
        Ok(self.format_entries(entries))
    }

    fn resolve(&self, working_directory: &str, path: &str) -> Result<PathBuf, String> {
        let logical = self.resolve_logical(working_directory, path);
        let candidate = self.root.join(logical);
        self.ensure_no_symlink_escape(&candidate)?;
        Ok(candidate)
    }

    fn resolve_logical(&self, working_directory: &str, path: &str) -> PathBuf {
        let trimmed = path.trim();
        let source = if trimmed.is_empty() || trimmed == "." {
            working_directory.trim_matches('/').to_string()
        } else if trimmed.starts_with('/') {
            trimmed.trim_start_matches('/').to_string()
        } else {
            [working_directory.trim_matches('/'), trimmed]
                .into_iter()
                .filter(|part| !part.is_empty())
                .collect::<Vec<_>>()
                .join("/")
        };

        let mut resolved = PathBuf::new();
        for component in Path::new(&source).components() {
            match component {
                Component::Normal(part) => resolved.push(part),
                Component::CurDir => {}
                Component::ParentDir => {
                    let _ = resolved.pop();
                }
                Component::RootDir | Component::Prefix(_) => {}
            }
        }
        resolved
    }

    fn ensure_no_symlink_escape(&self, target: &Path) -> Result<(), String> {
        if target.exists() {
            let canonical = target.canonicalize().map_err(|error| {
                format!(
                    "Cannot canonicalize native filesystem path `{}`: {error}",
                    target.display()
                )
            })?;
            if !canonical.starts_with(&self.root) {
                return Err(format!(
                    "Path escapes native filesystem root: {}",
                    target.display()
                ));
            }
            return Ok(());
        }

        let mut parent = target.parent();
        while let Some(candidate) = parent {
            if candidate.exists() {
                let canonical = candidate.canonicalize().map_err(|error| {
                    format!(
                        "Cannot canonicalize native filesystem parent `{}`: {error}",
                        candidate.display()
                    )
                })?;
                if !canonical.starts_with(&self.root) {
                    return Err(format!(
                        "Path escapes native filesystem root: {}",
                        target.display()
                    ));
                }
                return Ok(());
            }
            parent = candidate.parent();
        }
        Ok(())
    }

    fn ensure_within_quota(&self, target: &Path, new_size_bytes: u64) -> Result<(), String> {
        if self.quota_bytes == u64::MAX {
            return Ok(());
        }
        let existing_size = if target.exists() && target.is_file() {
            fs::metadata(target)
                .map_err(|error| {
                    format!(
                        "Cannot stat native filesystem file `{}`: {error}",
                        target.display()
                    )
                })?
                .len()
        } else {
            0
        };
        let used = self.current_usage()?;
        let next = used
            .saturating_sub(existing_size)
            .saturating_add(new_size_bytes);
        if next > self.quota_bytes {
            return Err(format!(
                "Disk quota exceeded: {next} > {}",
                self.quota_bytes
            ));
        }
        Ok(())
    }

    fn current_usage(&self) -> Result<u64, String> {
        let mut total = 0_u64;
        self.accumulate_usage(&self.root, &mut total)?;
        Ok(total)
    }

    fn accumulate_usage(&self, path: &Path, total: &mut u64) -> Result<(), String> {
        for entry in fs::read_dir(path).map_err(|error| {
            format!(
                "Cannot scan native filesystem directory `{}`: {error}",
                path.display()
            )
        })? {
            let path = entry.map_err(|error| error.to_string())?.path();
            let metadata = fs::symlink_metadata(&path).map_err(|error| {
                format!(
                    "Cannot stat native filesystem path `{}`: {error}",
                    path.display()
                )
            })?;
            if metadata.file_type().is_symlink() {
                continue;
            }
            if metadata.is_dir() {
                self.accumulate_usage(&path, total)?;
            } else if metadata.is_file() {
                *total = total.saturating_add(metadata.len());
            }
        }
        Ok(())
    }

    fn entry_for(&self, path: &Path) -> Result<WorkspaceEntry, String> {
        let name = path
            .file_name()
            .and_then(|name| name.to_str())
            .ok_or_else(|| {
                format!(
                    "Native filesystem entry has no valid UTF-8 name: {}",
                    path.display()
                )
            })?
            .to_string();
        Ok(WorkspaceEntry {
            name,
            directory: path.is_dir(),
        })
    }

    fn format_entries(&self, entries: Vec<WorkspaceEntry>) -> String {
        entries
            .into_iter()
            .map(|entry| {
                if entry.directory {
                    format!("{}/", entry.name)
                } else {
                    entry.name
                }
            })
            .collect::<Vec<_>>()
            .join(" ")
    }
}
