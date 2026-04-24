# Ultrasonic Chat (Android) – Testing Guide

## Overview

This app transmits text messages using near-ultrasonic sound (≈18–19 kHz) between devices.
This document defines what to test to verify correctness, stability, and real-world usability.

---

## Test Setup

### Devices

* Minimum: 2 Android phones
* Recommended: 2–3 different models (different brands)

### Environment

* Quiet room (initial testing)
* Devices placed:

  * 0.5m → 2m apart
  * Facing each other (speakers aligned)

---

## Phase 1: Basic App Validation

### 1. App Launch

* App opens without crash
* UI loads correctly
* Buttons visible:

  * Send
  * Receive

### 2. Permissions

* Microphone permission prompt appears
* Granting permission works
* App does not crash if denied

---

## Phase 2: Audio System Testing

### 3. Transmit Test (AudioTrack)

* Press Send (with test message)
* Verify:

  * No crash
  * Audio is being played
  * Slight high-pitched tone may be audible

### 4. Receive Test (AudioRecord)

* Start Receive mode
* Verify:

  * Mic is capturing input
  * No freezing or lag

---

## Phase 3: Signal Detection

### 5. Frequency Detection (Single Device)

* Play a constant tone (17900 Hz / 18900 Hz)
* Verify:

  * Correct frequency is detected
  * Logs show stable detection

### 6. Threshold Behavior

* Test in silence
* Verify:

  * No random bits detected
  * No false messages

---

## Phase 4: End-to-End Communication

### 7. Single Message Test

* Device A → Send "HELLO"
* Device B → Receive

Expected:

* Message decoded correctly
* No duplicate messages

---

### 8. Multiple Messages

* Send 3–5 messages sequentially

Expected:

* Each message received once
* No stale repeats
* No mixing of messages

---

### 9. Distance Test

Test at:

* 0.5 meters
* 1 meter
* 2 meters

Record:

* Success / failure
* Signal stability

---

### 10. Orientation Test

* Face-to-face
* Side-by-side
* Opposite direction

Expected:

* Best performance face-to-face

---

## Phase 5: Robustness Testing

### 11. Noise Test

* Add background noise (fan, talking)

Expected:

* Reduced performance
* No random garbage messages

---

### 12. Different Devices

* Test across different phones

Expected:

* Some variation in performance
* System still functional

---

### 13. Edge Case Inputs

Test messages:

* Short: "A"
* Long: "HELLO WORLD TEST"
* Special chars: "123!@#"

Expected:

* Correct decoding
* No crashes

---

## Phase 6: System Behavior

### 14. Duplicate Prevention

* Send one message

Expected:

* Only one decode
* No repeated prints

---

### 15. Cooldown Check

* Send message repeatedly fast

Expected:

* System does not spam outputs

---

### 16. Buffer Stability

* Run receive mode for 1–2 minutes

Expected:

* No memory growth
* No slowdown

---

## Common Failures & Fixes

### Issue: No detection

* Lower frequency:

  * Try 17900 / 18900

### Issue: Wrong decoding

* Increase bit duration (e.g., 0.12s)

### Issue: Noise triggers

* Increase energy threshold

### Issue: Works on one phone only

* Hardware limitation (expected)

---

## Success Criteria

The system is considered working if:

* Messages transmit successfully at short range (≤2m)
* No duplicate or stale messages
* Stable operation for multiple runs
* Works on at least 2 different devices

---

## Notes

* Ultrasonic communication depends heavily on hardware
* Performance will vary across phones
* This is a short-range, experimental system

---
