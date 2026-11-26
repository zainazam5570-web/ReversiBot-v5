# ♟ Reversi Bot (Android, Kotlin)

Screen dekh kar Reversi khud khelta hai. Strategy aap ke `reversi-coach-.html` ki hai
(same search + evaluation, Kotlin mein port; original JS se 30 positions par move/score/nodes barabar nikle).

## APK kaise banayein

**Tareeqa A: Android Studio**
1. Android Studio kholein > *Open* > is `ReversiBot` folder ko chunein, Gradle sync hone dein.
2. Phone USB se lagayen (USB debugging ON) > ▶ Run. Ya *Build > Build APK(s)* se `app-debug.apk` banayen.

**Tareeqa B: bina Android Studio (GitHub se)**
1. GitHub par naya repository banayen aur is folder ki saari files upload karein
   (`.github` folder samet).
2. *Actions* tab > *Build APK* ka run khatam hone dein (~3-5 min).
3. Run kholein > *Artifacts* > `ReversiBot-APK` download karein, zip khol kar `app-debug.apk` phone mein install karein.

## Pehli baar setup (phone par)

1. App kholein > **Overlay permission** ON.
2. **Accessibility settings** > *Reversi Bot Tapper* ON (ye sirf tap karti hai).
   Agar grey ho: Settings > Apps > Reversi Bot > ⋮ > *Allow restricted settings*, phir dobara.
3. **Bot shuru karein** > screen share mein **Entire screen / Poori screen** chunein
   ("Share one app" na chunein).
4. Game kholein. Side par panel aayega.
5. **Board auto-detect**: calibration ki zaroorat nahi. Game ki board wali screen par bot board khud dhoond leta hai
   (rotate karne ya board hatne par dobara dhoondta hai). Agar status mein "Board nahi mila" aaye to **📐 Manual** se 2 corners
   tap karein (ye sirf fallback hai).
6. Strength chunein (Fast and Strong / Very Strong / Maximum Search) aur **▶ START**.

Bot khud pehchanta hai ke baari aap ki hai (jab chaal ke hints nazar aayen) aur aap ka color
kaun sa hai (hints ko legal moves se match karke). Black/White ya "pehle kaun" batane ki zaroorat nahi.

## Tips

- **👁 View**: chhota board dikhata hai ke bot ko screen se kya nazar aa raha hai
  (kala/safed disc, peela dot = hint, hara ring = bot ki chaal). Pehle ▶ START dabaye baghair dekh lein
  ke detection sahi hai; START se pehle bot sirf dekhta aur sochta hai, tap nahi karta.
- Panel ko board ke **upar na rakhein** (screenshot mein panel aa jata hai). Header (⠿) pakar kar side mein khiskayen.
- Strength: Fast and Strong ~0.9s / depth 8, Very Strong ~1.6s / depth 10, Maximum Search ~2.6s / depth 12 (HTML ke same limits).

## Agar detection ghalat ho

Thresholds `vision/BoardDetector.kt` ke `DetectorConfig` mein hain (disc ka farq, black/white brightness,
hint dot ka farq). Game ka ek screenshot (hints ke saath) bhej dein to main un values ko us game ke hisab se tune kar dunga.

## Zaroori note

Online game mein doosre insaanon ke khilaf bot istemal karna aksar us game ke rules ke khilaf hota hai (account ban ho sakta hai).

## Code ka nakhsha

- `engine/ReversiEngine.kt` : rules + iterative-deepening alpha-beta (HTML ka port)
- `vision/BoardFinder.kt` : screen se board khud dhoondna
- `vision/BoardDetector.kt` : screen pixels se discs + hints, color ka andaza
- `bot/GameLoop.kt` : dekho > settle > socho > tap
- `ScreenCapture.kt` (MediaProjection), `BotAccessibilityService.kt` (tap), `OverlayPanel.kt`, `CalibrationOverlay.kt`, `BotService.kt`, `MainActivity.kt`

## v5 changes
- **Turn-pass banner fix**: bot ab board yaad rakhta hai. Banner/pop-up board ke upar aaye to woh frame ignore hota hai
  (tap/think nahi), banner hatte hi foran dobara padhta hai. Apni chaal ke baad agar opponent ke paas chaal na ho (pass)
  to agli chaal pehle se soch li jati hai. Opponent ki chaal ke baad board "mera board + ek legal chaal" se match kare to
  settle ka lamba intezar nahi hota.
- **Panel collapse**: ▾ dabane par sirf START/STOP aur ▴ (expand) bachte hain, panel utna hi chhota ho jata hai.
