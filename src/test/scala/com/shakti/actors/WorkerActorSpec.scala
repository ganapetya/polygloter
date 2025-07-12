package com.shakti.actors

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import com.shakti.actors.worker.WorkerActor
import org.scalatest.wordspec.AnyWordSpecLike

class WorkerActorSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  "WorkerActor" should {
    "process messages and maintain count" in {
      val worker = spawn(WorkerActor())
      
      worker ! WorkerActor.ProcessMessage("Test message 1")
      worker ! WorkerActor.ProcessMessage("Test message 2")
      
      val probe = createTestProbe[WorkerActor.Status]()
      worker ! WorkerActor.GetStatus(probe.ref)
      
      val status = probe.receiveMessage()
      status.processedCount shouldBe 2
      status.message shouldBe "Worker is running"
    }
    
    "stop when requested" in {
      val worker = spawn(WorkerActor())
      
      worker ! WorkerActor.Stop()
      
      // The actor should stop gracefully
      // We can verify this by checking that the actor is no longer alive
      // (though in a real test we might use a different approach)
    }
  }
} 