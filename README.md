# Water Physics (Fabric 1.21.1) — v0.1

## Cara build
1. Java 21 + Gradle 8.11+ (atau buka folder ini di IntelliJ IDEA, ia akan menyiapkan Gradle sendiri).
2. `gradle build` -> jar ada di `build/libs/waterphysics-0.1.0.jar`. Taruh di `mods/` bersama Fabric API 0.115.0+1.21.1.
   Untuk tes cepat: `gradle runClient`.
3. Kalau ada versi yang gagal di-resolve, samakan isi `gradle.properties` dengan https://fabricmc.net/develop

## Pemakaian (butuh OP / cheats)
- `/water ball <1-6>`   bola air, radius = angka, muncul di depanmu
- `/water convert <1-16>`  ganti air vanilla (sumber) di sekitarmu jadi air baru, dikerjakan bertahap
- `/water clear`        hapus partikel yang sedang bergerak
- `/water info`         jumlah partikel aktif
- Item **Water Emitter** (tab Tools): tahan klik kanan untuk menuang air terus-menerus

## Cara kerja singkat
- Air yang bergerak = partikel kecil (1 partikel = 1/8 blok air), disimulasikan di thread terpisah.
- Partikel yang sudah diam berubah jadi blok `level_water` (level 1-8) dan berhenti dihitung -> menuang tanpa batas tidak menambah beban.
- Kalau lantai di bawah blok air hilang, blok itu "bangun" lagi jadi partikel.
- Batas keras: 20.000 partikel aktif per dimensi. Kalau simulasi tak sempat selesai dalam satu tick, simulasi melambat, TPS server tetap.

## Uji inti simulasi tanpa Minecraft
```
javac -d out src/main/java/com/waterphysics/sim/FluidSim.java tools/SimTest.java
java -cp out SimTest 6
```

## Belum ada di v0.1
- Berenang / efek air pada pemain dan mob, perahu
- Mengganti mekanik air vanilla lewat Mixin (sekarang air baru hidup berdampingan, `/water convert` untuk mengganti yang sudah ada)
- Aliran horizontal blok yang sudah mengendap saat lantainya bolong di sebelah (hanya kolom di atas lubang yang bangun)
- Render partikel masih kubus sederhana (ringan untuk puluhan ribu? belum; batas kirim 8000 per paket)

## Build lewat GitHub Actions (tanpa install apa pun)
1. Unggah seluruh isi folder ini ke repositori GitHub baru (pastikan folder `.github` ikut terunggah).
2. Buka tab **Actions**, pilih run **build** terbaru, tunggu sampai hijau.
3. Di bagian **Artifacts** unduh `waterphysics-jar`. Pakai file `waterphysics-0.1.0.jar` (bukan yang `-sources`).
