# M4 local verification coverage and explicit device deferral

Each row preserves one of the 53 planned scenarios. “JVM assertions + source review” means related deterministic contracts are tested, not that the whole Android scenario was executed. “Source/pure supporting checks only” expressly leaves Android fault injection unexecuted. Exact run totals and failures are recorded separately in the final verification summary. No row is Android PASS.

| ID | Scenario | Local evidence scope | Device/platform execution |
|---|---|---|---|
| C01 | 同 key 连点开始、不同 key 并发开始 | JVM assertions + source review; RecordingControllerTest, OneShotGrantCellTest, RecordingNativeLeaseTest, RecordingControlOwnerTest, RecordingResultFenceTest | NOT_RUN |
| C02 | 用户取消确认、拒绝系统授权、空 result | Source/pure supporting checks only; RecordingControllerTest, OneShotGrantCellTest, RecordingNativeLeaseTest, RecordingControlOwnerTest, RecordingResultFenceTest | NOT_RUN |
| C03 | 迟到授权、旧 generation、重复 result | Source/pure supporting checks only; RecordingControllerTest, OneShotGrantCellTest, RecordingNativeLeaseTest, RecordingControlOwnerTest, RecordingResultFenceTest | NOT_RUN |
| C04 | 已同意但 entitlement 失效/用户撤销 | JVM assertions + source review; RecordingControllerTest, OneShotGrantCellTest, RecordingNativeLeaseTest, RecordingControlOwnerTest, RecordingResultFenceTest | NOT_RUN |
| C05 | 授权等待 Activity 重建与进程重建 | Source/pure supporting checks only; RecordingControllerTest, OneShotGrantCellTest, RecordingNativeLeaseTest, RecordingControlOwnerTest, RecordingResultFenceTest | NOT_RUN |
| C06 | FGS 启动/提升失败、getMediaProjection 异常 | Source/pure supporting checks only; RecordingControllerTest, OneShotGrantCellTest, RecordingNativeLeaseTest, RecordingControlOwnerTest, RecordingResultFenceTest | NOT_RUN |
| C07 | callback 在每个初始化步骤到达 | Source/pure supporting checks only; RecordingControllerTest, OneShotGrantCellTest, RecordingNativeLeaseTest, RecordingControlOwnerTest, RecordingResultFenceTest | NOT_RUN |
| C08 | 停止/取消/系统回调竞争 | JVM assertions + source review; RecordingControllerTest, OneShotGrantCellTest, RecordingNativeLeaseTest, RecordingControlOwnerTest, RecordingResultFenceTest | NOT_RUN |
| C09 | pause→pause、resume→resume、stop→stop | JVM assertions + source review; RecordingControllerTest, OneShotGrantCellTest, RecordingNativeLeaseTest, RecordingControlOwnerTest, RecordingResultFenceTest | NOT_RUN |
| C10 | paused 中系统停止、paused 中取消 | JVM assertions + source review; RecordingControllerTest, OneShotGrantCellTest, RecordingNativeLeaseTest, RecordingControlOwnerTest, RecordingResultFenceTest | NOT_RUN |
| C11 | 控制队列满、worker 拒绝、异常事件 | JVM assertions + source review; RecordingControllerTest, OneShotGrantCellTest, RecordingNativeLeaseTest, RecordingControlOwnerTest, RecordingResultFenceTest | NOT_RUN |
| C12 | deadline/资源达到上限 | JVM assertions + source review; RecordingControllerTest, OneShotGrantCellTest, RecordingNativeLeaseTest, RecordingControlOwnerTest, RecordingResultFenceTest | NOT_RUN |
| C13 | native 忽略 interrupt | JVM assertions + source review; RecordingControllerTest, OneShotGrantCellTest, RecordingNativeLeaseTest, RecordingControlOwnerTest, RecordingResultFenceTest | NOT_RUN |
| T01 | 20 秒＋暂停 120 秒＋30 秒时间线 | JVM assertions + source review; RecordingTimelineTest, RecordingCaptureAccountingTest | NOT_RUN |
| T02 | 多次快速 pause/resume、stop 在切换间隙 | JVM assertions + source review; RecordingTimelineTest, RecordingCaptureAccountingTest | NOT_RUN |
| T03 | resume 未立即给关键帧、永远不给关键帧 | JVM assertions + source review; RecordingTimelineTest, RecordingCaptureAccountingTest | NOT_RUN |
| T04 | AAC 在视频恢复前后到达、packet 跨边界 | JVM assertions + source review; RecordingTimelineTest, RecordingCaptureAccountingTest | NOT_RUN |
| T05 | 墙钟跳变、单调钟异常、长时间值 | JVM assertions + source review; RecordingTimelineTest, RecordingCaptureAccountingTest | NOT_RUN |
| T06 | 视频静止无样本、用户暂停 | JVM assertions + source review; RecordingTimelineTest, RecordingCaptureAccountingTest | NOT_RUN |
| M01 | 视频/音频格式先后顺序、格式永不来 | JVM assertions + source review; RecordingContractsTest, RecordingProfileGeometryTest, RecordingCaptureAccountingTest, RecordingVideoTailStateTest, RecordingMp4TimingProbeTest | NOT_RUN |
| M02 | codec-config、空 buffer、错 track、PTS 倒退 | JVM assertions + source review; RecordingContractsTest, RecordingProfileGeometryTest, RecordingCaptureAccountingTest, RecordingVideoTailStateTest, RecordingMp4TimingProbeTest | NOT_RUN |
| M03 | 空文件/无关键帧/极短录制/损坏尾部 | JVM assertions + source review; RecordingContractsTest, RecordingProfileGeometryTest, RecordingCaptureAccountingTest, RecordingVideoTailStateTest, RecordingMp4TimingProbeTest | NOT_RUN |
| M04 | EOS 到达顺序变化、muxer.stop 失败 | Source/pure supporting checks only; RecordingContractsTest, RecordingProfileGeometryTest, RecordingCaptureAccountingTest, RecordingVideoTailStateTest, RecordingMp4TimingProbeTest | NOT_RUN |
| M05 | 最大样本/队列/文件字节/剩余磁盘边界 | JVM assertions + source review; RecordingContractsTest, RecordingProfileGeometryTest, RecordingCaptureAccountingTest, RecordingVideoTailStateTest, RecordingMp4TimingProbeTest | NOT_RUN |
| M06 | 尺寸/profile/对齐/能力不支持 | Source/pure supporting checks only; RecordingContractsTest, RecordingProfileGeometryTest, RecordingCaptureAccountingTest, RecordingVideoTailStateTest, RecordingMp4TimingProbeTest | NOT_RUN |
| M07 | 尺寸变化/旋转重复事件 | Source/pure supporting checks only; RecordingContractsTest, RecordingProfileGeometryTest, RecordingCaptureAccountingTest, RecordingVideoTailStateTest, RecordingMp4TimingProbeTest | NOT_RUN |
| A01 | 选择无声音 | Source/pure supporting checks only; RecordingContractsTest, RecordingControllerTest, RecordingCoordinatorTest, SaveUiPolicyTest | NOT_RUN |
| A02 | 内部音频权限拒绝 | Source/pure supporting checks only; RecordingContractsTest, RecordingControllerTest, RecordingCoordinatorTest, SaveUiPolicyTest | NOT_RUN |
| A03 | 全零 PCM、低音量、正常 PCM | Source/pure supporting checks only; RecordingContractsTest, RecordingControllerTest, RecordingCoordinatorTest, SaveUiPolicyTest | NOT_RUN |
| A04 | AudioRecord 初始化/读取失败 | Source/pure supporting checks only; RecordingContractsTest, RecordingControllerTest, RecordingCoordinatorTest, SaveUiPolicyTest | NOT_RUN |
| A05 | 声音配置承诺有声但产物缺轨/不可读 | JVM assertions + source review; RecordingContractsTest, RecordingControllerTest, RecordingCoordinatorTest, SaveUiPolicyTest | NOT_RUN |
| S01 | typed network/capture request 混用 | JVM assertions + source review; RecordingCoordinatorTest, SaveRecordCodecTest, PrivateCommitProtocolTest, RecordingResultFenceTest | NOT_RUN |
| S02 | Direct/Stream 失败、取消、DRM、storage error | JVM assertions + source review; RecordingCoordinatorTest, SaveRecordCodecTest, PrivateCommitProtocolTest, RecordingResultFenceTest | NOT_RUN |
| S03 | 页面背景 pause | JVM assertions + source review; RecordingCoordinatorTest, SaveRecordCodecTest, PrivateCommitProtocolTest, RecordingResultFenceTest | NOT_RUN |
| S04 | stopAndSave 与 Cancel | JVM assertions + source review; RecordingCoordinatorTest, SaveRecordCodecTest, PrivateCommitProtocolTest, RecordingResultFenceTest | NOT_RUN |
| S05 | 收尾成功→校验失败/超时 | JVM assertions + source review; RecordingCoordinatorTest, SaveRecordCodecTest, PrivateCommitProtocolTest, RecordingResultFenceTest | NOT_RUN |
| S06 | verify→commit→inspect→SUCCEEDED 各步 crash | JVM assertions + source review; RecordingCoordinatorTest, SaveRecordCodecTest, PrivateCommitProtocolTest, RecordingResultFenceTest | NOT_RUN |
| S07 | writer/native FD 仍占用 | JVM assertions + source review; RecordingCoordinatorTest, SaveRecordCodecTest, PrivateCommitProtocolTest, RecordingResultFenceTest | NOT_RUN |
| S08 | 旧 v1/v2 records、新 recording record、坏 discriminator | JVM assertions + source review; RecordingCoordinatorTest, SaveRecordCodecTest, PrivateCommitProtocolTest, RecordingResultFenceTest | NOT_RUN |
| S09 | owned evidence blob 旧/新格式混合 | JVM assertions + source review; RecordingCoordinatorTest, SaveRecordCodecTest, PrivateCommitProtocolTest, RecordingResultFenceTest | NOT_RUN |
| S10 | 录制进程崩溃、旧 projection 无效 | JVM assertions + source review; RecordingCoordinatorTest, SaveRecordCodecTest, PrivateCommitProtocolTest, RecordingResultFenceTest | NOT_RUN |
| S11 | 给 recording result 伪造 HLS expectation/反向伪造 | JVM assertions + source review; RecordingCoordinatorTest, SaveRecordCodecTest, PrivateCommitProtocolTest, RecordingResultFenceTest | NOT_RUN |
| E01 | 免费、RECORD only、Tools only、all、过期/撤销/未知 | JVM assertions + source review; core-domain entitlement tests, RecordingControllerTest, source/release bytecode guards | NOT_RUN |
| E02 | deep link/service action/notification 绕 UI | Source/pure supporting checks only; core-domain entitlement tests, RecordingControllerTest, source/release bytecode guards | NOT_RUN |
| E03 | 拒绝权益后 Stop/Cancel/成片预览 | JVM assertions + source review; core-domain entitlement tests, RecordingControllerTest, source/release bytecode guards | NOT_RUN |
| E04 | Release 源码/字节依赖扫描 | JVM assertions + source review; core-domain entitlement tests, RecordingControllerTest, source/release bytecode guards | NOT_RUN |
| U01 | task method label/stage/audio/no-percent | JVM assertions + source review; SaveUiPolicyTest, PrivateCommitProtocolTest, ui-theme tests | NOT_RUN |
| U02 | preview 请求 staging/provisional/伪路径 | JVM assertions + source review; SaveUiPolicyTest, PrivateCommitProtocolTest, ui-theme tests | NOT_RUN |
| U03 | preview close/新录制/播放器错误 | Source/pure supporting checks only; SaveUiPolicyTest, PrivateCommitProtocolTest, ui-theme tests | NOT_RUN |
| U04 | SkinKit 七类、日夜、字体缩放、大屏策略 | Source/pure supporting checks only; SaveUiPolicyTest, PrivateCommitProtocolTest, ui-theme tests | NOT_RUN |
| P01 | journal/saved state/log/notification 快照 | Source/pure supporting checks only; SaveRecordCodecTest, check-boundaries.py, check-recording-admission.py, exact 72-artifact admission | NOT_RUN |
| P02 | dependency graph/license/source diff | JVM assertions + source review; SaveRecordCodecTest, check-boundaries.py, check-recording-admission.py, exact 72-artifact admission | NOT_RUN |
| R01 | 全部原 Direct/HLS/Commons/Discovery 测试 | JVM assertions + source review; aggregate Gradle tests, three frozen HLS host runners, SQLite and detector regression scripts | NOT_RUN |

## Required physical matrix (all NOT_RUN)

- API31 Android 12 phone: per-session consent, foreground start, video-only and permitted audio, deny/cancel, start/pause/resume/stop, native independent MP4 decode
- API32 Android 12L tablet: aspect ratio, density, split screen, Activity reconstruction, actual initial canvas behavior
- API33: denied and allowed notification permission; notification/system Stop
- API34: one-use grant and one VirtualDisplay, app/full-screen sharing, first vs later content resize
- API35 and 35 QPR1+: system projection chip, lock screen, competing projection, stop callback
- API36 phone and tablet/600dp+: adaptive orientation/window behavior, FGS and audio permissions
- Multiple OEMs: actual codec support and release, audio policy, thermal/low-space behavior
- End-to-end 20 seconds + 120 seconds paused + 30 seconds gives about 50 seconds of independent output; 30-minute AV drift; sample/tail decode, audible permitted fixture, silent/blocked fixture, resource release

No APK/AAB, installation, permission prompt, real capture, external account, billing, publication or deployment was performed. Fake JVM contracts do not substitute for this matrix.

## Final policy B work limits

- No full-track AVC/AAC native decoding. Full-file hash and full structural/sample/timing scans remain; actual decoding covers complete first, temporal-middle, final, and effective-resume GOPs.
- At most 32 effective resume boundaries, counted only after the corresponding keyframe is successfully muxed. Resume request 33 safely finalizes existing output; stop/cancel precedence remains sticky. Start and control UI disclose the cap; warning begins at 28 and exact count is shown.
- At most 35 distinct selected video GOPs; shared first/middle/final/resume roles are decoded once. Every rolling GOP is at most 600 samples and 8 MiB compressed input; distinct selected input totals at most 32 MiB. The rolling bound also applies to an unselected GOP because it could become the terminal GOP.
- Full scan is at most 2 GiB and 10 million combined video/audio samples. MP4 atom walking and stts accounting are bounded, independent of duration metadata.
- AAC readability windows cover start/middle/end and in-range resumed timestamps: at most 64 access units and 1 MiB per window, 4 MiB total. This is sparse audio decode, not every-packet validation.
- All scanning, hashing and native decoding share a 60-second verification deadline and cancellation checks. The same native lane stays fenced if a call ignores cancellation. A deadline failure never publishes unchecked media.
- The 2-hour session and 2-GiB artifact limits are resource ceilings, not an established supported duration or throughput promise. Valid long/high-resolution output may fail the bounded verification window. Real performance is NOT_RUN.
- Task codec v5 and owned evidence v3 preserve genuine earlier formats, including WIP v4 RECORD/evidence v2 with null resume-policy sentinel. Such older unfinished records stay readable but cannot be promoted to new success without the missing proof.
