# RH PRO RACING TIMER v4 (starter source)

## Fitur
- Login/daftar email-password dan login Google memakai Firebase Authentication.
- GPS real-time menggunakan Fused Location Provider.
- Timer otomatis mulai saat kecepatan GPS mencapai 10 km/jam.
- Target selesai dapat diubah di aplikasi (default 60 km/jam).
- Riwayat per akun disimpan di Cloud Firestore.

## Setup Firebase wajib sebelum build
1. Buat project di Firebase Console.
2. Daftarkan Android app dengan package `com.rhpro.racetimer`.
3. Unduh `google-services.json` lalu letakkan di folder `app/`.
4. Aktifkan Authentication > Sign-in methods: Email/Password dan Google.
5. Tambahkan SHA-1 dan SHA-256 signing certificate aplikasi pada pengaturan Android app Firebase.
6. Salin Web client ID dari Firebase/Google Cloud ke `default_web_client_id` pada `app/src/main/res/values/strings.xml`.
7. Buat Cloud Firestore database.
8. Buka project di Android Studio, tunggu Gradle Sync, lalu Build > Build APK(s).

## Firestore Rules
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{uid}/runs/{runId} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
  }
}

## Catatan
Ini adalah fondasi v4, bukan APK final yang sudah diuji di perangkat. GPS perlu langit terbuka dan izin lokasi. Pengukuran GPS punya jeda dan fluktuasi; gunakan hanya di area tertutup yang aman. Pengukuran saat ini berjalan ketika aplikasi terbuka, belum mendukung layar terkunci/background.
