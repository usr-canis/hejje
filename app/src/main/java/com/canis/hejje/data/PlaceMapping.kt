package com.canis.hejje.data

import com.canis.hejje.data.db.ZoneEntity
import com.canis.hejje.domain.NamedPlace

fun ZoneEntity.toPlace() = NamedPlace(id, name, lat, lon, radiusM, icon, hide, tag)
