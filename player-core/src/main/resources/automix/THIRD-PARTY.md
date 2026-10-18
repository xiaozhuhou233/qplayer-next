# Third-party code

## JCodec — MP3 decoding

The **22 files** under `src/main/java/org/jcodec/` come from [JCodec](https://jcodec.org)
(`org.jcodec:jcodec:0.2.5`) and are under the **FreeBSD** license — a permissive license,
**not LGPL**. It does not require this project to be open source and carries no source
disclosure obligation.

- `org/jcodec/codecs/mpa/` — the MPEG audio (Layer III) decoder itself: 7 files
- `org/jcodec/common/`, `org/jcodec/common/io/`, `org/jcodec/common/model/`,
  `org/jcodec/common/tools/` — the utility classes the decoder actually references: 15 files

Only the slice needed for MP3 decoding was taken. The rest of JCodec (containers, video, and
the **JAAD AAC decoder bundled inside it — that one is LGPL**) is **not** included.

### Local changes

Relative to upstream `0.2.5` only three things were cut, all to keep the closure at those 22
files:

1. Three `NIOUtils` calls in `Mp3Decoder` (`read` / `skip` / `relocateLeftover`) were inlined,
   so `NIOUtils` and its `org.jcodec.platform` dependency are not needed;
2. The **encoder-only** `writeVLC` methods were removed from `VLC` / `VLCBuilder`, so `BitWriter`
   is not needed;
3. Consequently `org/jcodec/platform/` and `org/jcodec/common/tools/ToJSON.java` are not pulled
   in either.

**No decoding logic was changed.** Decoding was verified against a reference: the same MP3
decoded with this decoder and with ffmpeg shows a maximum per-sample difference of **69 / 32767**
(about 0.2 %) after alignment, the difference coming only from synthesis filterbank rounding.

---

```
Copyright 2008-2019 JCodecProject

Redistribution  and  use  in   source  and   binary   forms,  with  or  without
modification, are permitted provided  that the following  conditions  are  met:

Redistributions of  source code  must  retain the above  copyright notice, this
list of conditions and the following disclaimer. Redistributions in binary form
must  reproduce  the above  copyright notice,  this  list of conditions  and the
following disclaimer in the documentation and/or other  materials provided with
the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,  BUT NOT LIMITED TO, THE  IMPLIED
WARRANTIES  OF  MERCHANTABILITY  AND  FITNESS  FOR  A  PARTICULAR  PURPOSE  ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY,  OR CONSEQUENTIAL DAMAGES
(INCLUDING,  BUT NOT LIMITED TO,  PROCUREMENT OF SUBSTITUTE GOODS  OR SERVICES;
LOSS OF USE, DATA, OR PROFITS;  OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY  THEORY  OF  LIABILITY,  WHETHER  IN  CONTRACT, STRICT LIABILITY,  OR TORT
(INCLUDING  NEGLIGENCE OR OTHERWISE)  ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```
