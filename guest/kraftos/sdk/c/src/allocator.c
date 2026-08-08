#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

struct allocation {
  size_t size;
  unsigned int is_free;
  struct allocation *next;
  unsigned int reserved;
};

static struct allocation *first_allocation;

static size_t aligned_size(size_t size) {
  return (size + 7u) & ~7u;
}

static void split_allocation(struct allocation *block, size_t size) {
  if (block->size < size + sizeof(struct allocation) + 8u) return;
  struct allocation *tail =
      (struct allocation *)((unsigned char *)(block + 1) + size);
  tail->size = block->size - size - sizeof(struct allocation);
  tail->is_free = 1;
  tail->next = block->next;
  tail->reserved = 0;
  block->size = size;
  block->next = tail;
}

void *malloc(size_t size) {
  struct allocation *block;
  struct allocation *last = NULL;
  size_t requested;

  if (size == 0) size = 1;
  if (size > UINT32_MAX - 7u - sizeof(struct allocation)) {
    errno = ENOMEM;
    return NULL;
  }
  requested = aligned_size(size);
  for (block = first_allocation; block != NULL; block = block->next) {
    if (block->is_free && block->size >= requested) {
      split_allocation(block, requested);
      block->is_free = 0;
      return block + 1;
    }
    last = block;
  }
  block = sbrk((int)(sizeof(struct allocation) + requested));
  if (block == (void *)-1) {
    errno = ENOMEM;
    return NULL;
  }
  block->size = requested;
  block->is_free = 0;
  block->next = NULL;
  block->reserved = 0;
  if (last != NULL) last->next = block;
  else first_allocation = block;
  return block + 1;
}

void free(void *pointer) {
  struct allocation *block;
  struct allocation *cursor;
  if (pointer == NULL) return;
  block = (struct allocation *)pointer - 1;
  block->is_free = 1;
  for (cursor = first_allocation; cursor != NULL && cursor->next != NULL;) {
    unsigned char *end =
        (unsigned char *)(cursor + 1) + cursor->size;
    if (cursor->is_free && cursor->next->is_free &&
        end == (unsigned char *)cursor->next) {
      cursor->size += sizeof(struct allocation) + cursor->next->size;
      cursor->next = cursor->next->next;
    } else {
      cursor = cursor->next;
    }
  }
}

void *calloc(size_t count, size_t size) {
  size_t total;
  void *result;
  if (size != 0 && count > UINT32_MAX / size) {
    errno = ENOMEM;
    return NULL;
  }
  total = count * size;
  result = malloc(total);
  if (result != NULL) memset(result, 0, total);
  return result;
}

void *realloc(void *pointer, size_t size) {
  struct allocation *block;
  void *replacement;
  size_t copy_size;
  if (pointer == NULL) return malloc(size);
  if (size == 0) {
    free(pointer);
    return NULL;
  }
  block = (struct allocation *)pointer - 1;
  if (block->size >= size) {
    split_allocation(block, aligned_size(size));
    return pointer;
  }
  replacement = malloc(size);
  if (replacement == NULL) return NULL;
  copy_size = block->size < size ? block->size : size;
  memcpy(replacement, pointer, copy_size);
  free(pointer);
  return replacement;
}

void abort(void) { _exit(134); }
void exit(int status) { _exit(status); }
