#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

extern uint32_t kernel(uint32_t iterations);

static uint32_t parse_positive_u32(const char *name, const char *text) {
    char *end = NULL;
    errno = 0;
    unsigned long value = strtoul(text, &end, 10);
    if (errno != 0 || end == text || *end != '\0' || value == 0 || value > UINT32_MAX) {
        fprintf(stderr, "%s must be an integer in 1..%" PRIu32 "\n", name, UINT32_MAX);
        exit(2);
    }
    return (uint32_t)value;
}

static uint64_t elapsed_nanos(struct timespec start, struct timespec end) {
    uint64_t seconds = (uint64_t)(end.tv_sec - start.tv_sec);
    int64_t nanos = end.tv_nsec - start.tv_nsec;
    if (nanos < 0) {
        --seconds;
        nanos += 1000000000ll;
    }
    return seconds * 1000000000ull + (uint64_t)nanos;
}

static int compare_u64(const void *left, const void *right) {
    uint64_t a = *(const uint64_t *)left;
    uint64_t b = *(const uint64_t *)right;
    return (a > b) - (a < b);
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: %s ITERATIONS ODD_SAMPLES\n", argv[0]);
        return 2;
    }
    uint32_t iterations = parse_positive_u32("iterations", argv[1]);
    uint32_t samples = parse_positive_u32("samples", argv[2]);
    if ((samples & 1u) == 0 || samples > 1001u) {
        fprintf(stderr, "samples must be odd and at most 1001\n");
        return 2;
    }

    uint64_t durations[1001];
    uint32_t checksum = kernel(iterations);
    for (uint32_t sample = 0; sample < samples; ++sample) {
        struct timespec start;
        struct timespec end;
        if (clock_gettime(CLOCK_MONOTONIC_RAW, &start) != 0) {
            perror("clock_gettime start");
            return 1;
        }
        uint32_t actual = kernel(iterations);
        if (clock_gettime(CLOCK_MONOTONIC_RAW, &end) != 0) {
            perror("clock_gettime end");
            return 1;
        }
        if (actual != checksum) {
            fprintf(stderr, "native C checksum changed between samples\n");
            return 1;
        }
        durations[sample] = elapsed_nanos(start, end);
    }
    qsort(durations, samples, sizeof(durations[0]), compare_u64);
    uint32_t p95_index = (samples * 95u + 99u) / 100u - 1u;
    printf("checksum=%" PRIu32 "\n", checksum);
    printf("warm_median_ns=%" PRIu64 "\n", durations[samples / 2u]);
    printf("warm_p95_ns=%" PRIu64 "\n", durations[p95_index]);
    return 0;
}
