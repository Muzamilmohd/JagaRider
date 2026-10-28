# 🏍️ JagaRider

Semak hujan sebelum ride (Android 8.0+).

- **Tahap hujan live** di lokasi anda: Tiada hujan → Renyai → Ringan → Sederhana → Lebat → Sangat lebat (mm/jam)
- **Peta hujan** 5 / 10 / 25 / 50 km: taburan hujan dilukis atas peta (OpenStreetMap), arah & jarak hujan terdekat, ribut petir ⚡
- **Ramalan 2 jam** setiap 15 minit: bila hujan dijangka mula / reda
- Keputusan ringkas: 🟢 Cuaca baik untuk ride / 🟡 / 🟠 / 🔴 Tangguh dulu
- Dikemas kini automatik setiap 5 minit semasa app dibuka
- **Kemas kini sendiri** dari GitHub Releases (semak setiap hari, boleh dimatikan)

## Cara pasang
1. Muat turun fail `JagaRider-v*.apk` dari [Releases](https://github.com/Muzamilmohd/JagaRider/releases/latest).
2. Buka fail tu dan benarkan "Pasang app tidak diketahui" bila diminta.
3. Benarkan akses lokasi supaya app boleh semak hujan di sekeliling anda.

## Privasi
Lokasi hanya digunakan semasa app dibuka. Koordinat titik semakan dihantar ke `api.open-meteo.com`
untuk data cuaca, dan jubin peta kawasan sekitar dimuat turun dari `tile.openstreetmap.org`; app juga
meminta maklumat release dari `api.github.com`. Tiada data lain dihantar.

Data cuaca: [Open-Meteo.com](https://open-meteo.com) (CC BY 4.0).
Peta: © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors. Ramalan model — bukan jaminan.
