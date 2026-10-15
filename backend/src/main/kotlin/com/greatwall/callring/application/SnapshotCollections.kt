package com.greatwall.callring.application

import java.util.Collections

internal fun <T> frozenList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

internal fun <T> frozenSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

internal fun <K, V> frozenMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))
