package com.ludovictemgoua.imdb.domain.repository;

// Shared by every repository method backing a PUT/PATCH update or a DELETE on a versioned entity
// (users, titles, people, reviews, lists, ...) - lets the use-case layer distinguish "no such row"
// from "row exists but your version is stale" without the repository itself deciding which HTTP
// status or domain exception that becomes (that stays an application-layer decision, matching how
// NotFoundException is already thrown by use cases today, not repositories).
public enum WriteResult { SUCCESS, NOT_FOUND, VERSION_CONFLICT }
