# NAAT STUDIO PRO — सिर्फ iPhone से TestFlight Setup

> **स्थिति:** iOS Simulator compilation PASS. App Store Connect upload नहीं हुआ है। Signed IPA अभी उपलब्ध नहीं। Apple Developer membership, signing और असली iPhone testing बाकी है।

## 1. Apple Developer enrollment (India)

1. iPhone में [Apple Developer](https://apps.apple.com/app/apple-developer/id640199958) install करें।
2. वही Apple Account use करें जिसमें two-factor authentication enabled हो।
3. Apple Developer > Account > Enroll Now. Individual या Organization चुने; Individual में seller के तौर पर आपका legal name दिख सकता है।
4. Apple की identity verification, agreements और payment पूरा करें। Program की सामान्य कीमत US$99/year है, भारतीय शुल्क app के checkout में देखें।
5. Account activation email आने और Apple Developer membership **Active** होने के बाद आगे बढ़ें।

## 2. App Store Connect

1. Safari में [App Store Connect](https://appstoreconnect.apple.com/) खोलें।
2. **Users and Access > Integrations > App Store Connect API** में Account Holder से API access request/approval कराएँ।
3. **Team Keys > Generate API Key**: केवल जरूरी permissions वाला key बनाएँ। Key ID, Issuer ID नोट करें। .p8 key केवल एक बार download होती है। **इसे चैट, सार्वजनिक repo या किसी व्यक्ति को न भेजें।**
4. Apple Developer > Membership से Team ID नोट करें।
5. **Certificates, Identifiers & Profiles > Identifiers** में explicit Bundle ID `com.naatstudio.pro.ios` register करें। उपलब्ध न हो तो `ios/project.yml` का PRODUCT_BUNDLE_IDENTIFIER बदलकर अलग ID register करें।
6. App Store Connect > Apps > **+** > New App: Name **NAAT STUDIO PRO**, Platform iOS, सही Bundle ID, SKU `NAATSTUDIOIOS001` (या दूसरा unique SKU), language और agreements पूरा करें।
7. App icon/metadata, age rating, privacy answers और export compliance सही भरें। पहला TestFlight external build review से गुजरेगा।

## 3. GitHub Secrets और Variables

GitHub में अपने private repo [avesh1111/-Naat-Studio-PRO](https://github.com/avesh1111/-Naat-Studio-PRO) खोलें। Safari में जरूरत पड़े तो **Request Desktop Website** करें। Settings > Secrets and variables > Actions:

**Repository Variables (non-secret):**
- `APPLE_TEAM_ID` = आपका Apple developer Team ID
- `ASC_API_KEY_ID` = generated Team API key का Key ID
- `ASC_ISSUER_ID` = App Store Connect API का Issuer ID

**Repository Secret (private):**
- `ASC_API_PRIVATE_KEY` = डाउनलोड की हुई AuthKey_*.p8 file का पूरा private key content, BEGIN/END PRIVATE KEY समेत

GitHub encrypted Secrets में key डालें। ChatGPT में Apple Password, verification code, recovery key, .p8 private key **कभी paste न करें**।

**किसी भी unexpected GitHub Actions request पर UPLOAD न चलाएँ**; वह Apple पर build भेजेगा।

## 4. GitHub cloud Mac पर signing और TestFlight upload

Repository > Actions > **iOS TestFlight (Manual Upload)** > Run workflow > `confirm_upload` में `UPLOAD` टाइप करें।

यह workflow:
- GitHub macOS runner पर XcodeGen + Xcode इस्तेमाल करता है
- private key temporary runner file में रखता है
- Apple automatic provisioning की कोशिश करता है
- signed archive बनाता है और Apple के server पर export/upload करता है
- job खत्म होने पर temporary key हटा देता है

कभी-कभी Apple API permissions, signing assets, app record, export compliance, iOS icon या provisioning के कारण job fail हो सकता है; तब Actions logs के error को देखकर सुधारें। **Simulator build PASS** होने से App Store upload success guaranteed नहीं होती।

## 5. iPhone पर TestFlight से install

App Store Connect > Apps > NAAT STUDIO PRO > TestFlight. Uploaded build का Processing पूरा होने के बाद Internal Testing में tester group बनाएँ और अपने Apple Account को user/tester access दें। External tester groups के लिए Apple beta review लागू हो सकती है। iPhone पर [TestFlight](https://apps.apple.com/app/testflight/id899247664) install करें, email invitation या redeem code के जरिए beta install करें।

Real-device smoke tests: profile saved on iPhone; 61 presets; mobile mic WAV; Bluetooth route; Live Mehfil USB input/output and channel separation; recording; safe monitoring levels. Bluetooth latency खत्म नहीं होगी।

## ज्ञात सीमाएँ

- Android APK iPhone में install नहीं होगी।
- App का current iOS code software audio approximation है; stage-low-latency या exact BOSS sound की गारंटी नहीं।
- कोई Apple account credentials इस repo में store नहीं किए गए।
- Cloud upload अभी user credentials मिलने के बाद ही संभव होगा।
