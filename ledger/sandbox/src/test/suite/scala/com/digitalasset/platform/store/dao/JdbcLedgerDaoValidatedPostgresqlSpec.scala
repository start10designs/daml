// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.store.dao

import org.scalatest.{AsyncFlatSpec, Matchers}

// Aggregate all specs in a single run to not start a new database fixture for each one
final class JdbcLedgerDaoValidatedPostgresqlSpec
    extends AsyncFlatSpec
    with Matchers
    with JdbcLedgerDaoSuite
    with JdbcLedgerDaoBackendPostgresql
    with JdbcLedgerDaoPostCommitValidationSpec
