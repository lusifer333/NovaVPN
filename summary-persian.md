# خلاصه وضعیت NovaVPN برای انتقال به conversation جدید

## آخرین نسخه: v0.4.9-alpha

### وضعیت فعلی
- ✅ Server selection works
- ✅ VPN permission dialog shows
- ❌ Xray binary error: "Engine binary not found in assets or native libs!"
- ✅ APK: https://github.com/lusifer333/NovaVPN/releases/tag/v0.4.9-alpha

### مشکل اصلی: `extractNativeLibs="false"`
در merged manifest خط ۳۲ مقدار `android:extractNativeLibs="false"` هست — این مقدار پیش‌فرض AGP برای `minSdk >= 23` هست.

**تاثیر:** وقتی `extractNativeLibs="false"` باشه، سیستم Android فایل‌های `.so` رو از jniLibs به `nativeLibraryDir` استخراج نمی‌کنه. در نتیجه:
- `context.applicationInfo.nativeLibraryDir/libxray.so` وجود نداره → `nativeBinaryFile().exists()` = false
- کد به fallback assets می‌ره → assets/engines/ هم خالیه (فایل‌ها به jniLibs منتقل شدن)
- خطا: "Engine binary not found in assets or native libs!"

**مشکل دوم:** حجم APK بالا (~85MB) چون هم `libxray.so` (33MB) و هم `libsingbox.so` (52MB) توی jniLibs هست. کاربر فقط Xray می‌خواد.

### راه حل
۱. اضافه کردن `android:extractNativeLibs="true"` به `AndroidManifest.xml`
۲. حذف `libsingbox.so` از jniLibs (فقط xray بمونه)
۳. jniLibs از assets قابل اعتمادتر هست چون SELinux context صحیح داره
۴. script `download-engines.sh` باید به jniLibs بنویسه، نه assets

### ساختار jniLibs (بعد از cleanup)
```
app/src/main/jniLibs/
└── arm64-v8a/
    └── libxray.so    (33MB, فقط xray)
```

### نکات فنی
- Android از نسخه ۶.۰+ (API 23) به بعد، AGP پیش‌فرض `extractNativeLibs=false` تنظیم می‌کنه
- V2RayNG و Matsuri از assets استفاده می‌کنن + extract در runtime
- روش jniLibs برای error=13 بهتر جواب میده چون SELinux context درسته
- ولی باید حتماً `extractNativeLibs=true` باشه تا jniLibs استخراج بشن
- `ApplicationInfo.nativeLibraryDir` فقط وقتی فایل داره که `extractNativeLibs=true` باشه