# Vendored YunX provider code

The files in `source/core/network/src/main/kotlin/com/yunx/app/` with the upstream YunX copyright header are from [YunX v1.3.1](https://github.com/CYQawa/YunX/tree/v1.3.1), commit `648a16d001d5fc8f61fff75ac5b14d266d44cd12`. The upstream AGPL-3.0-or-later notices are retained; see [YunX licensing terms](LICENSE-YunX-AGPL-3.0). The DataBackup portions retain their original GPL-3.0 terms; see the combination provisions of GPLv3 and AGPLv3 section 13.

Adaptations: file-backed range source for finalized archives; exclude the WebView-only desktop hints helper from the protocol layer; use a dedicated OkHttp client and disable the vendor diagnostic sink to avoid recording account and response data. These sources are not yet exposed as a backup destination.
