package com.example.tandemapp.data

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class PumpEventsStreamingTest {
    @Test
    fun streamingMatchesStringDecodeAndPreservesRawMetadata() {
        val payload = """
            {
              "clockChanges":[{"before":"2026-11-01T01:00:00Z","after":"2026-11-01T02:00:00Z"}],
              "unknownTop":{"nested":[true,null,123.45]},
              "events":[
                {
                  "pumpDateTime":"2026-11-01T12:00:00",
                  "estimatedDateTime":"2026-11-01T12:00:01Z",
                  "deviceAssignmentId":"assignment-1",
                  "eventCode":399,
                  "sequenceGroup":2,
                  "sequenceNumber":7,
                  "eventProperties":{
                    "egvTimeStamp":562420800,
                    "currentGlucoseDisplayValue":125,
                    "cgmDataType":[1],
                    "unknownProperty":{"raw":[1,2,3]}
                  }
                },
                {
                  "pumpDateTime":"2026-11-01T11:59:00Z",
                  "eventCode":191,
                  "sequenceGroup":1,
                  "sequenceNumber":5,
                  "eventProperties":{"unrecognized":true}
                }
              ]
            }
        """.trimIndent()
        val expected = PumpEventsAdapter.decode(payload, "assignment-1")
        val actual = ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8)).use {
            PumpEventsAdapter.decodeStream(it, "assignment-1")
        }
        assertEquals(expected.cgm, actual.cgm)
        assertEquals(expected.bolus, actual.bolus)
        assertEquals(expected.carbs, actual.carbs)
        assertEquals(expected.iob, actual.iob)
        assertEquals(expected.basal, actual.basal)
        assertEquals(expected.deviceStates, actual.deviceStates)
        assertEquals(expected.supplementalEvents, actual.supplementalEvents)
        assertEquals(expected.pumpEventsSources, actual.pumpEventsSources)
        assertEquals(0, actual.cgm.single().source?.payloadIndex)
        assertEquals(0, actual.cgm.single().source?.eventIndex)
    }
}
