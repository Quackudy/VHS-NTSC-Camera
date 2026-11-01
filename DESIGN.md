---
name: VHS Camera
description: A viewfinder-first personal camcorder with native Android controls.
colors:
  viewfinder-background: "#000000"
  record: "#c62828"
  on-record: "#ffffff"
typography:
  camera-title:
    fontFamily: "Android system sans"
    fontSize: "22sp"
  capture-status:
    fontFamily: "Android system sans"
    fontSize: "14sp"
spacing:
  control-gap: "8dp"
  screen-horizontal: "12dp"
  settings-padding: "16dp"
components:
  record-button:
    backgroundColor: "{colors.record}"
    textColor: "{colors.on-record}"
    width: "80dp"
    height: "80dp"
---

## Overview

Operate mode: shooting and watching the footage lead. The implemented Android
Views screen uses a dark platform Material theme; developer tools and export
screens use the existing light platform theme. This records the v0.6 code. Portrait and
landscape ready screens at normal and 1.3x font scale, plus landscape Settings,
were checked on the A26; active recording/paused appearance was not captured.

## Colors

Black frames the video without changing its pixels. Red and white identify the
record/stop action; paused state is also named in text. Other native controls
inherit the platform theme rather than carrying a separate custom palette.

## Typography

Use Android system text and sp sizing for settings and shooting controls.
Settings dialogs follow platform typography. Do not apply decorative text or
artificial VHS artifacts to the user interface.

## Layout

Keep the preview at its correct fitted 4:3 framing, without stretching or cropping
to fill the screen. Portrait places W/T and record controls below it. Landscape
moves them to a 280dp scrolling rail. Apply system/cutout insets. Settings contain
configuration and measurements; recording, pause/resume and timer stay visible.
Touch targets are at least 48dp; the record button is 80dp. Scrolling provides
access to controls when space or Android font scale is constrained.

## Shapes

The record/stop action is circular. Other buttons, checkboxes, spinners, dialog
surfaces and focus/pressed feedback retain native platform treatments.

## Components

Pause becomes Resume within the same recording. The timer freezes while paused.
Settings and Gallery cannot interrupt an active or paused recording. The capture
label distinguishes live effect from original preview. The footer explains that
originals record clean; processing creates the final VHS copy separately.
Developer mode retains the original image/video proof and detailed measurements.
