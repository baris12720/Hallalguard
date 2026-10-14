# HelalGuard v2.1.0 — Google Play Store Yayınlama & APK Derleme Rehberi

Bu proje, **HelalGuard (Helal & Boykot Tarayıcı)** uygulamasının Google Play Store (Hedef API Seviyesi 35 / Android 15 & 16 uyumlu) için optimize edilmiş tam Android Studio kaynak kodunu içerir.

---

## 1. Hızlı Başlangıç (Android Studio ile Açma)

1. Bilgisayarınızda [Android Studio](https://developer.android.com/studio) programını açın.
2. **Open** (veya *File > Open*) seçeneğine tıklayın.
3. İndirdiğiniz bu klasörü (`HelalGuard_Android_Project`) seçin ve **OK** deyin.
4. Gradle senkronizasyonu otomatik başlayacaktır. Tamamlanmasını bekleyin.

---

## 2. Test İçin Hızlı APK Alma (Telefonunuza Yüklemek İçin)

1. Android Studio üst menüsünden:
   `Build` > `Build Bundle(s) / APK(s)` > `Build APK(s)` seçeneğine tıklayın.
2. 1-2 dakika içinde sağ alt köşede **"Build APK(s): APK(s) generated successfully"** bildirimi çıkacaktır.
3. **locate** butonuna tıklayarak oluşturulan `app-debug.apk` dosyasını bulun.
4. Bu `.apk` dosyasını telefonunuza WhatsApp, Telegram, USB veya e-posta ile gönderip kurun.

---

## 3. Google Play Store İçin İmzalı AAB (Android App Bundle) Üretme

Google Play artık `.apk` yerine `.aab` formatını zorunlu tutmaktadır.

1. Android Studio menüsünden:
   `Build` > `Generate Signed Bundle / APK...` seçeneğine tıklayın.
2. **Android App Bundle** seçeneğini işaretleyip **Next** deyin.
3. **Key store path** altında:
   - Eğer ilk kez yapıyorsanız **Create new...** deyin.
   - Bilgisayarınızda güvenli bir konuma (örn: Masaüstü) `helalguard-release.jks` adıyla kaydedin.
   - Şifrelerinizi belirleyin ve not edin (asla kaybetmeyin).
   - "First and Last Name" alanına adınızı veya şirket adınızı yazıp **OK** deyin.
4. **Next** deyin, derleme türü olarak **release** seçin ve **Finish** butonuna basın.
5. Birkaç dakika sonra Google Play Console'a yüklenecek `.aab` dosyanız hazır olacaktır!

---

## 4. Google Play Console Mağaza Bilgileri

Google Play Console'da yeni uygulama oluştururken şu bilgileri kullanabilirsiniz:

- **Uygulama Adı:** HelalGuard - Helal & Boykot Tarayıcı
- **Kısa Açıklama:** Barkod & etiket tarama, E-kodu fıkhi analizi, boykot kontrolü ve yerli alternatifler.
- **Tam Açıklama:**
  HelalGuard; Müslüman tüketicilerin market raflarında helal, temiz ve etik ürünleri hızlıca tespit edebilmeleri, boykotlu çatı şirketlerin ürünlerinden sakınıp yerli ve bağımsız alternatifleri tercih edebilmeleri amacıyla geliştirilmiş bağımsız bir platformdur.
  - Canlı kamera ve galeriden etiket fotoğrafı ile barkod tarama
  - E120 Karmin, E471, Jelatin gibi E-kodlarının fıkhi ve bilimsel köken analizi (GİMDES, JAKIM, MUI)
  - Şeffaf boykot gerekçeleri ve onaylı yerli alternatif önerileri
  - Türkçe, İngilizce ve Arapça (RTL) tam dil desteği
  - %100 çevrimdışı (internetsiz) çalışma desteği
- **Kategori:** Yiyecek ve İçecek / Alışveriş
- **İçerik Derecelendirmesi (IARC):** 3+ (Herkes için uygun)
- **Hedef Kitle:** 13 yaş ve üzeri
- **Uygulama İzinleri:** Yalnızca ürün ambalajı ve barkod okumak için `CAMERA` izni.

Hayırlı ve bereketli olsun!
