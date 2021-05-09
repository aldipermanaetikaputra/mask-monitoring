[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
# Mask Monitoring (PakaiMasker)
 
A monitoring app helps you to remind mask usage while you are using your phone to protect againts Coronavirus.

Introduction
------------

Our focus to help everyone to adhere health protocols by using mask. This app implement classification model in device, so this app can fully work without any internet connection. 

We use foreground service, so you don't always have to be in the application, our monitoring is always running even you're using another application.

Resources
------------

Python Wrapper is required for running classification to predict usage of mask, so we don't send any picture of yourself to our server / cloud, your privacy is completley safe. 
We use great SDK which is [Chaquopy](https://chaquo.com/chaquopy/) to help us implement Python components in our Android app.

In terms of prediction models, we are using an excellent open source model called [FaceMaskPrediction](https://github.com/AIZOOTech/FaceMaskDetection), which is able to accurately predict for this use case.
