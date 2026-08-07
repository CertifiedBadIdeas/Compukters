/* K16 TinyCC byte and half-word memory ABI fixture. */
static unsigned char bytes[3] = { 0x80u, 7u, 3u };
static unsigned short words[2] = { 0x1234u, 5u };

int main(void) {
  signed char s = (signed char)bytes[0];
  bytes[1] = (unsigned char)(bytes[1] + 4u);
  words[1] = (unsigned short)(words[1] + bytes[2]);
  return (s < 0 ? 64 : 0) + bytes[1] + words[1] + 8;
}
