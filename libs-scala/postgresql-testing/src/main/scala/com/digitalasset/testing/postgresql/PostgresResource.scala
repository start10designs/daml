// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.testing.postgresql

import com.daml.resources.{Resource, ResourceOwner}

import scala.concurrent.{ExecutionContext, Future}

object PostgresResource {
  def owner(): ResourceOwner[PostgresDatabase] =
    new ResourceOwner[PostgresDatabase] with PostgresAround {
      override def acquire()(
          implicit executionContext: ExecutionContext
      ): Resource[PostgresDatabase] =
        Resource(Future {
          connectToPostgresqlServer()
          createNewRandomDatabase()
        })(_ => Future(disconnectFromPostgresqlServer()))
    }
}
