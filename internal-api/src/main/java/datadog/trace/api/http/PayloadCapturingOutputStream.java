package datadog.trace.api.http;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Wraps an OutputStream to capture payload data for tracing.
 * Optimized for minimal overhead - only captures up to configured limit.
 * <p>
 * This class transparently captures data as it flows through the stream,
 * with no impact on the consuming application. Once the limit is reached,
 * it stops buffering and continues to pass data through unchanged.
 */
public class PayloadCapturingOutputStream extends FilterOutputStream {

  private final ByteArrayOutputStream buffer;
  private final int maxSize;
  private final Charset charset;
  private int totalWritten = 0;
  private boolean limitReached = false;

  /**
   * Creates a new payload capturing output stream.
   *
   * @param out the underlying output stream
   * @param maxSize maximum number of bytes to capture (0 = unlimited, but not recommended)
   * @param charset character encoding for the payload (null = UTF-8)
   */
  public PayloadCapturingOutputStream(
      OutputStream out,
      int maxSize,
      Charset charset) {
    super(out);
    this.maxSize = maxSize > 0 ? maxSize : Integer.MAX_VALUE;
    this.charset = charset != null ? charset : StandardCharsets.UTF_8;
    // Pre-allocate to avoid resizing - use smaller of maxSize or 8KB
    this.buffer = new ByteArrayOutputStream(Math.min(this.maxSize, 8192));
  }

  @Override
  public void write(int b) throws IOException {
    super.write(b);
    if (!limitReached && totalWritten < maxSize) {
      buffer.write(b);
      totalWritten++;
      if (totalWritten >= maxSize) {
        limitReached = true;
      }
    }
  }

  @Override
  public void write(byte[] b) throws IOException {
    write(b, 0, b.length);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    super.write(b, off, len);
    if (!limitReached) {
      int toCapture = Math.min(len, maxSize - totalWritten);
      if (toCapture > 0) {
        buffer.write(b, off, toCapture);
        totalWritten += toCapture;
        if (totalWritten >= maxSize) {
          limitReached = true;
        }
      }
    }
  }

  /**
   * Returns captured payload as string.
   * Call this after stream is flushed/closed or at the point you want to tag.
   *
   * @return the captured payload as a string, or empty string if nothing captured
   */
  public String getCapturedPayload() {
    if (buffer.size() == 0) {
      return "";
    }
    try {
      return new String(buffer.toByteArray(), charset);
    } catch (Exception e) {
      // Fallback to default charset if conversion fails
      return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  /**
   * Returns the raw captured bytes.
   *
   * @return byte array of captured data
   */
  public byte[] getCapturedBytes() {
    return buffer.toByteArray();
  }

  /**
   * Returns true if we've reached the capture limit.
   *
   * @return true if limit reached, false otherwise
   */
  public boolean isLimitReached() {
    return limitReached;
  }

  /**
   * Returns the number of bytes captured so far.
   *
   * @return bytes captured
   */
  public int getBytesWritten() {
    return totalWritten;
  }
}
