#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define FILE_READABLE 1u
#define FILE_WRITABLE 2u
#define FILE_APPEND 4u
#define FILE_UNBUFFERED 8u

struct __kraft_file {
  int descriptor;
  unsigned int flags;
  int error;
  int end_of_file;
  int allocated;
  long position;
  unsigned char buffer[BUFSIZ];
  size_t buffer_position;
  size_t buffer_length;
  struct __kraft_file *next_open;
};

static struct __kraft_file standard_input = {
    STDIN_FILENO, FILE_READABLE, 0, 0, 0, 0, {0}, 0, 0, NULL};
static struct __kraft_file standard_output = {
    STDOUT_FILENO, FILE_WRITABLE | FILE_UNBUFFERED, 0, 0, 0, 0, {0}, 0, 0, NULL};
static struct __kraft_file standard_error = {
    STDERR_FILENO, FILE_WRITABLE | FILE_UNBUFFERED, 0, 0, 0, 0, {0}, 0, 0, NULL};

FILE *stdin = &standard_input;
FILE *stdout = &standard_output;
FILE *stderr = &standard_error;
static FILE *open_streams;

struct open_mode {
  int flags;
  unsigned int stream_flags;
  int append;
};

static int parse_mode(const char *mode, struct open_mode *result) {
  char operation;
  size_t index;
  if (mode == NULL || mode[0] == 0) {
    errno = EINVAL;
    return -1;
  }
  operation = mode[0];
  for (index = 1; mode[index] != 0; index += 1) {
    if (mode[index] == 'b') continue;
    errno = EINVAL;
    return -1;
  }
  result->append = 0;
  if (operation == 'r') {
    result->flags = O_RDONLY;
    result->stream_flags = FILE_READABLE;
  } else if (operation == 'w') {
    result->flags = O_WRONLY | O_CREAT | O_TRUNC;
    result->stream_flags = FILE_WRITABLE;
  } else if (operation == 'a') {
    result->flags = O_WRONLY | O_CREAT | O_APPEND;
    result->stream_flags = FILE_WRITABLE | FILE_APPEND;
    result->append = 1;
  } else {
    errno = EINVAL;
    return -1;
  }
  return 0;
}

static void initialize_stream(
    FILE *stream,
    int descriptor,
    unsigned int flags,
    int allocated,
    long position
) {
  stream->descriptor = descriptor;
  stream->flags = flags;
  stream->error = 0;
  stream->end_of_file = 0;
  stream->allocated = allocated;
  stream->position = position;
  stream->buffer_position = 0;
  stream->buffer_length = 0;
}

static void register_stream(FILE *stream) {
  stream->next_open = open_streams;
  open_streams = stream;
}

static void unregister_stream(FILE *stream) {
  FILE **cursor = &open_streams;
  while (*cursor != NULL) {
    if (*cursor == stream) {
      *cursor = stream->next_open;
      stream->next_open = NULL;
      return;
    }
    cursor = &(*cursor)->next_open;
  }
}

static int flush_write_buffer(FILE *stream) {
  size_t written = 0;
  long append_start = stream->position - (long)stream->buffer_length;
  if (stream->buffer_length == 0) return 0;
  if ((stream->flags & FILE_APPEND) != 0) {
    append_start = lseek(stream->descriptor, 0, SEEK_END);
    if (append_start < 0) {
      stream->error = 1;
      return EOF;
    }
  }
  while (written < stream->buffer_length) {
    ssize_t result = write(
        stream->descriptor,
        stream->buffer + written,
        stream->buffer_length - written
    );
    if (result <= 0) {
      if (written != 0) {
        memmove(
            stream->buffer,
            stream->buffer + written,
            stream->buffer_length - written
        );
        stream->buffer_length -= written;
      }
      stream->error = 1;
      return EOF;
    }
    written += (size_t)result;
  }
  if ((stream->flags & FILE_APPEND) != 0) {
    stream->position = append_start + (long)stream->buffer_length;
  }
  stream->buffer_length = 0;
  return 0;
}

FILE *fopen(const char *path, const char *mode) {
  struct open_mode parsed;
  FILE *stream;
  int descriptor;
  long position = 0;
  if (path == NULL || parse_mode(mode, &parsed) != 0) return NULL;
  descriptor = open(path, parsed.flags);
  if (descriptor < 0) return NULL;
  if (parsed.append) {
    position = lseek(descriptor, 0, SEEK_END);
    if (position < 0) {
      int saved_errno = errno;
      close(descriptor);
      errno = saved_errno;
      return NULL;
    }
  }
  stream = malloc(sizeof(FILE));
  if (stream == NULL) {
    int saved_errno = errno;
    close(descriptor);
    errno = saved_errno;
    return NULL;
  }
  initialize_stream(stream, descriptor, parsed.stream_flags, 1, position);
  register_stream(stream);
  return stream;
}

FILE *fdopen(int descriptor, const char *mode) {
  struct open_mode parsed;
  FILE *stream;
  long position = 0;
  if (descriptor < 0 || parse_mode(mode, &parsed) != 0) {
    if (descriptor < 0) errno = EBADF;
    return NULL;
  }
  if (parsed.append) {
    position = lseek(descriptor, 0, SEEK_END);
    if (position < 0) return NULL;
  }
  stream = malloc(sizeof(FILE));
  if (stream == NULL) return NULL;
  initialize_stream(stream, descriptor, parsed.stream_flags, 1, position);
  register_stream(stream);
  return stream;
}

FILE *freopen(const char *path, const char *mode, FILE *stream) {
  struct open_mode parsed;
  int descriptor;
  long position = 0;
  int allocated;
  if (stream == NULL || path == NULL || parse_mode(mode, &parsed) != 0) return NULL;
  allocated = stream->allocated;
  if (fflush(stream) != 0) return NULL;
  if (close(stream->descriptor) != 0) return NULL;
  descriptor = open(path, parsed.flags);
  if (descriptor < 0) return NULL;
  if (parsed.append) {
    position = lseek(descriptor, 0, SEEK_END);
    if (position < 0) {
      int saved_errno = errno;
      close(descriptor);
      errno = saved_errno;
      return NULL;
    }
  }
  initialize_stream(stream, descriptor, parsed.stream_flags, allocated, position);
  return stream;
}

int fflush(FILE *stream) {
  int result = 0;
  if (stream == NULL) {
    FILE *cursor;
    if (flush_write_buffer(stdout) != 0) result = EOF;
    if (flush_write_buffer(stderr) != 0) result = EOF;
    for (cursor = open_streams; cursor != NULL; cursor = cursor->next_open) {
      if ((cursor->flags & FILE_WRITABLE) != 0 && flush_write_buffer(cursor) != 0) {
        result = EOF;
      }
    }
    return result;
  }
  if ((stream->flags & FILE_WRITABLE) == 0) return 0;
  return flush_write_buffer(stream);
}

int fclose(FILE *stream) {
  int flush_result;
  int close_result;
  int allocated;
  if (stream == NULL) {
    errno = EINVAL;
    return EOF;
  }
  allocated = stream->allocated;
  flush_result = fflush(stream);
  close_result = close(stream->descriptor);
  if (allocated) {
    unregister_stream(stream);
    free(stream);
  }
  return flush_result == 0 && close_result == 0 ? 0 : EOF;
}

size_t fwrite(const void *buffer, size_t size, size_t count, FILE *stream) {
  const unsigned char *source = buffer;
  size_t total;
  size_t accepted = 0;
  if (size == 0 || count == 0) return 0;
  if (stream == NULL || (stream->flags & FILE_WRITABLE) == 0 ||
      count > UINT32_MAX / size) {
    if (stream != NULL) stream->error = 1;
    errno = stream == NULL ? EINVAL : EBADF;
    return 0;
  }
  total = size * count;
  if ((stream->flags & FILE_APPEND) != 0 && stream->buffer_length == 0) {
    long end = lseek(stream->descriptor, 0, SEEK_END);
    if (end < 0) {
      stream->error = 1;
      return 0;
    }
    stream->position = end;
  }
  while (accepted < total) {
    size_t accepted_before_copy;
    size_t available;
    size_t copied;
    if (stream->buffer_length == BUFSIZ && flush_write_buffer(stream) != 0) break;
    available = BUFSIZ - stream->buffer_length;
    copied = total - accepted < available ? total - accepted : available;
    accepted_before_copy = accepted;
    memcpy(stream->buffer + stream->buffer_length, source + accepted, copied);
    stream->buffer_length += copied;
    accepted += copied;
    stream->position += (long)copied;
    if ((stream->buffer_length == BUFSIZ || (stream->flags & FILE_UNBUFFERED) != 0) &&
        flush_write_buffer(stream) != 0) {
      accepted = accepted_before_copy;
      break;
    }
  }
  return accepted / size;
}

size_t fread(void *buffer, size_t size, size_t count, FILE *stream) {
  unsigned char *destination = buffer;
  size_t total;
  size_t delivered = 0;
  if (size == 0 || count == 0) return 0;
  if (stream == NULL || (stream->flags & FILE_READABLE) == 0 ||
      count > UINT32_MAX / size) {
    if (stream != NULL) stream->error = 1;
    errno = stream == NULL ? EINVAL : EBADF;
    return 0;
  }
  total = size * count;
  while (delivered < total) {
    size_t available;
    size_t copied;
    if (stream->buffer_position == stream->buffer_length) {
      ssize_t result = read(stream->descriptor, stream->buffer, BUFSIZ);
      stream->buffer_position = 0;
      stream->buffer_length = 0;
      if (result == 0) {
        stream->end_of_file = 1;
        break;
      }
      if (result < 0) {
        stream->error = 1;
        break;
      }
      stream->buffer_length = (size_t)result;
    }
    available = stream->buffer_length - stream->buffer_position;
    copied = total - delivered < available ? total - delivered : available;
    memcpy(destination + delivered, stream->buffer + stream->buffer_position, copied);
    stream->buffer_position += copied;
    delivered += copied;
    stream->position += (long)copied;
  }
  return delivered / size;
}

int fseek(FILE *stream, long offset, int origin) {
  long target;
  long physical_position;
  off_t result;
  if (stream == NULL) {
    errno = EINVAL;
    return -1;
  }
  if (fflush(stream) != 0) return -1;
  physical_position = stream->position;
  if ((stream->flags & FILE_READABLE) != 0) {
    physical_position += (long)(stream->buffer_length - stream->buffer_position);
  }
  if (origin == SEEK_SET) {
    target = offset;
  } else if (origin == SEEK_CUR) {
    if ((offset > 0 && stream->position > LONG_MAX - offset) ||
        (offset < 0 && stream->position < LONG_MIN - offset)) {
      errno = EINVAL;
      return -1;
    }
    target = stream->position + offset;
  } else if (origin == SEEK_END) {
    long end = lseek(stream->descriptor, 0, SEEK_END);
    if (end < 0) {
      stream->error = 1;
      return -1;
    }
    if ((offset > 0 && end > LONG_MAX - offset) ||
        (offset < 0 && end < LONG_MIN - offset)) {
      int saved_errno = EINVAL;
      lseek(stream->descriptor, physical_position, SEEK_SET);
      errno = saved_errno;
      return -1;
    }
    target = end + offset;
    if (target == end) {
      result = end;
    } else {
      result = lseek(stream->descriptor, target, SEEK_SET);
      if (result < 0) {
        int saved_errno = errno;
        lseek(stream->descriptor, physical_position, SEEK_SET);
        errno = saved_errno;
        stream->error = 1;
        return -1;
      }
    }
  } else {
    errno = EINVAL;
    return -1;
  }
  if (origin != SEEK_END) {
    result = lseek(stream->descriptor, target, SEEK_SET);
    if (result < 0) {
      stream->error = 1;
      return -1;
    }
  }
  stream->buffer_position = 0;
  stream->buffer_length = 0;
  stream->position = result;
  stream->end_of_file = 0;
  return 0;
}

long ftell(FILE *stream) {
  if (stream == NULL) {
    errno = EINVAL;
    return -1;
  }
  return stream->position;
}

int fgetc(FILE *stream) {
  unsigned char character;
  return fread(&character, 1, 1, stream) == 1 ? character : EOF;
}

char *fgets(char *buffer, int size, FILE *stream) {
  int length = 0;
  if (buffer == NULL || size <= 0) {
    errno = EINVAL;
    return NULL;
  }
  while (length + 1 < size) {
    int character = fgetc(stream);
    if (character == EOF) break;
    buffer[length++] = (char)character;
    if (character == '\n') break;
  }
  if (length == 0) return NULL;
  buffer[length] = 0;
  return buffer;
}

int fputc(int character, FILE *stream) {
  unsigned char byte = (unsigned char)character;
  return fwrite(&byte, 1, 1, stream) == 1 ? byte : EOF;
}

int fputs(const char *text, FILE *stream) {
  size_t length = strlen(text);
  return fwrite(text, 1, length, stream) == length ? 0 : EOF;
}

int fileno(FILE *stream) {
  if (stream == NULL) {
    errno = EINVAL;
    return -1;
  }
  return stream->descriptor;
}

int vfprintf(FILE *stream, const char *format, va_list arguments) {
  va_list measure_arguments;
  va_list render_arguments;
  char *buffer;
  int length;
  int rendered;
  va_copy(measure_arguments, arguments);
  length = vsnprintf(NULL, 0, format, measure_arguments);
  va_end(measure_arguments);
  if (length < 0) return -1;
  buffer = malloc((size_t)length + 1);
  if (buffer == NULL) return -1;
  va_copy(render_arguments, arguments);
  rendered = vsnprintf(buffer, (size_t)length + 1, format, render_arguments);
  va_end(render_arguments);
  if (rendered >= 0 && fwrite(buffer, 1, (size_t)rendered, stream) != (size_t)rendered) {
    rendered = -1;
  }
  free(buffer);
  return rendered;
}

int fprintf(FILE *stream, const char *format, ...) {
  va_list arguments;
  int result;
  va_start(arguments, format);
  result = vfprintf(stream, format, arguments);
  va_end(arguments);
  return result;
}

int vprintf(const char *format, va_list arguments) {
  return vfprintf(stdout, format, arguments);
}

int printf(const char *format, ...) {
  va_list arguments;
  int result;
  va_start(arguments, format);
  result = vfprintf(stdout, format, arguments);
  va_end(arguments);
  return result;
}

int getchar(void) { return fgetc(stdin); }

int putchar(int character) { return fputc(character, stdout); }

int puts(const char *text) {
  if (fputs(text, stdout) == EOF || fputc('\n', stdout) == EOF) return EOF;
  return 0;
}

void perror(const char *prefix) {
  if (prefix != NULL && prefix[0] != 0) {
    fputs(prefix, stderr);
    fputs(": ", stderr);
  }
  fputs(strerror(errno), stderr);
  fputc('\n', stderr);
}
