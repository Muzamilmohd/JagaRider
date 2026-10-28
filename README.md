# 🏍️ JagaRide

Semak hujan sebelum ride (Android 8.0+).

- **Radar hujan sebenar** (RainViewer) untuk hujan sekarang — tahap hujan live di lokasi anda: Tiada hujan → Renyai → Ringan → Sederhana → Lebat → Sangat lebat (mm/jam)
- **Peta hujan** dengan slider 5 km hingga seluruh Semenanjung Malaysia (rancang ride jauh): taburan hujan dilukis atas peta (OpenStreetMap), arah & jarak hujan terdekat, ribut petir ⚡
- **Ramalan 2 jam** setiap 15 minit dengan ikon cuaca: bila hujan dijangka mula / reda
- **Ramalan 6 jam**: ikon, suhu & peluang hujan (%) setiap jam — jam berpeluang hujan tinggi diserlahkan
- Keputusan ringkas: 🟢 Cuaca baik untuk ride / 🟡 / 🟠 / 🔴 Tangguh dulu
- Dikemas kini automatik setiap 5 minit semasa app dibuka
- **Amaran hujan 🔔**: notifikasi berbunyi bila hujan dalam 5 / 10 / 25 km (ikut lokasi live jika lokasi dibenarkan sepanjang masa) — bunyi hujan untuk hujan biasa, hujan + guruh untuk hujan lebat / ribut petir
- **Tetapan ⚙️**: bahasa (Melayu / English / 中文 / தமிழ்) dan mod paparan (ikut sistem / cerah / gelap) dan format jam 12 / 24 jam
- **Kemas kini sendiri** dari GitHub Releases (semak setiap hari, boleh dimatikan)

## Cara pasang
1. Muat turun fail `JagaRide-v*.apk` dari [Releases](https://github.com/Muzamilmohd/JagaRider/releases/latest).
2. Buka fail tu dan benarkan "Pasang app tidak diketahui" bila diminta.
3. Benarkan akses lokasi supaya JagaRide boleh semak hujan di sekeliling anda.

## Privasi
Lokasi digunakan semasa app dibuka, dan di latar untuk amaran hujan hanya jika anda pilih "Benarkan sepanjang masa". Koordinat titik semakan dihantar ke `api.open-meteo.com`
untuk data cuaca, imej radar dimuat turun dari RainViewer, dan jubin peta kawasan sekitar dimuat turun dari `tile.openstreetmap.org`; app juga
meminta maklumat release dari `api.github.com`. Tiada data lain dihantar.

Ramalan cuaca: [Open-Meteo.com](https://open-meteo.com) (CC BY 4.0). Radar hujan: [RainViewer](https://www.rainviewer.com).
Peta: © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors. Ramalan model — bukan jaminan.

Dibangunkan oleh **Muzamil Mohd**.
