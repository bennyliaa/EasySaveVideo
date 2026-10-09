# M10-B — Channel readiness checklist

Research checked: **2026-10-07 (UTC)**. This is a dated preparation checklist, not
legal advice, a submission result, or evidence that a market has accepted ESV.

## Confirmed scope and current state

- Intended first-launch destinations: **Google Play, Huawei AppGallery, Xiaomi,
  OPPO, vivo, and Tencent MyApp (应用宝)**.
- The existing Android build sets `minSdk = 31`, `targetSdk = 36`, and
  `compileSdk = 36`. This document does not change those values.
- M10-B adds explicit distribution identities only. Every identity still uses the
  unconfigured store adapter; an identity is not proof of installation source,
  account eligibility, billing availability, or market approval.
- Payment strategy is **UNDECIDED**. Paid download, in-app purchase, product,
  pricing, SDK and commercial-term decisions are outside this checklist.
- For **each of the six destinations**, developer-account ownership/verification,
  account eligibility, signing/certificate registration, submission, review and
  publication status are **UNKNOWN**. No account or submission was inspected.
- APP filing status, software copyright documentation, rights-holder identity and
  any category-specific licence status are **UNKNOWN**.

## Requirements applicable to the planned release

### Mainland China APP filing and identity consistency

The MIIT notice applies to APP operators providing internet information services
in mainland China. It requires the relevant filing, visible in-app filing number
and a link to the filing system; distribution platforms must not distribute
covered APPs that have not fulfilled the filing requirement. ESV's planned
internet-enabled functionality must not be treated as a purely offline exception
without confirming its actual eligibility.

The notice's **20-working-day** period starts when the provincial communications
authority receives **complete and accurate materials**. It is not an end-to-end
promise from the start of preparation, and does not include store review time.
Freeze the intended application name, package identity and rights-holder details
before checking the filing and store materials for consistency.

Source: [MIIT APP filing notice, clauses 1, 7–9](https://www.miit.gov.cn/jgsj/xgj/wjfb/art/2023/art_dd783a581c9644a4aee10afa582811db.html).

### Google Play Android target

The current official page states that, from **2026-08-31**, ordinary mobile new
apps and app updates must target **Android 16 / API 36 or higher**. This is a
`targetSdk` requirement, not a requirement to raise `minSdk` to 36. The existing
ESV target value meets this numerical requirement; that alone proves neither
runtime compatibility nor overall Play acceptance.

Source: [Google Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en-EN).

## Account-dependent and channel-specific gates

- **Google Play account gate:** personal developer accounts created after
  **2023-11-13** require a closed test with **at least 12 testers opted in
  continuously for at least 14 days**, followed by an application for production
  access. Meeting the test duration does not automatically grant production
  access. ESV's account type, creation date and applicability are unknown.
  [Official testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en).
- **Google Play publication timing:** Google advises at least a week of review
  buffer; processing may take hours, up to seven days, or longer in exceptional
  cases. Its managed-publishing guidance says the app must already be available
  and recommends a closed testing track before a managed product launch. Do not
  assume a first upload can be held and released like an existing app update.
  [Official review/publication guidance](https://support.google.com/googleplay/android-developer/answer/9859654?hl=en-en).
- **Xiaomi:** the official qualification FAQ says pending software copyright
  paperwork does not permit submission first and supplementation later. It also
  requires consistency among application, filing and copyright names. Completed
  acceptable rights documentation must be checked before submission.
  [Xiaomi qualification FAQ](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2251).
- **OPPO:** its official operations guide requires preparing the name and package
  and filing before creating/submitting the app, with an exception stated for
  offline resources. The official review FAQ requires a directly accessible
  Chinese static-HTML privacy policy, rather than a PDF, image or cloud document.
  [Upload guide](https://open.oppomobile.com/bbs/forum.php?mod=viewthread&tid=6304),
  [review FAQ](https://open.oppomobile.com/bbs/forum.php?mod=viewthread&tid=6307).
- **Huawei:** the official AppGallery entry still describes APK submission;
  native HarmonyOS has its own SDK/toolchain and APP-package submission route.
  The selected Huawei Android destination does **not** establish native
  HarmonyOS NEXT support or compatibility with all Huawei devices.
  [APK distribution entry](https://developer.huawei.com/consumer/cn/appgallery/),
  [native HarmonyOS submission](https://developer.huawei.com/consumer/cn/app/submit).
- **vivo:** the official review-rules URL was located, but its current rule body
  was not returned completely by public retrieval. Detailed current requirements
  remain **UNVERIFIED**; no rule from another channel or a game programme is
  substituted. [Official rules entry](https://dev.vivo.com.cn/documentCenter/doc/12).
- **Tencent MyApp:** the official developer platform was located, but its current
  submission/review rule body was unavailable through public retrieval. Detailed
  current requirements remain **UNVERIFIED**.
  [Official developer platform](https://app.open.qq.com/p/).

## Release decision boundary

Before scheduling launch, verify the actual accounts and qualification materials,
resolve the two incomplete rule checks, recheck current policies, complete the
applicable testing/review steps, and confirm each market's publication controls.
The six-destination plan does not guarantee synchronized approval, simultaneous
download availability, or store acceptance. This checklist authorizes no account,
certificate, signing, payment, upload, or publication operation.
