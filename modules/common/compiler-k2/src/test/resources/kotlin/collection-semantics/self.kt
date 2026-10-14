/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

fun main() {
    val selfList = ArrayList<Any?>()
    selfList.add(selfList)
    check(selfList.toString() == "[(this Collection)]")
    val selfSet = HashSet<Any?>()
    selfSet.add(selfSet)
    check(selfSet.toString() == "[(this Collection)]")
    val selfMap = HashMap<Any?, Any?>()
    selfMap.put("self", selfMap)
    check(selfMap.toString() == "{self=(this Map)}")
    val selfKey = HashMap<Any?, Any?>()
    selfKey.put(selfKey, "self")
    check(selfKey.toString() == "{(this Map)=self}")
    println("collection self ok")
}
