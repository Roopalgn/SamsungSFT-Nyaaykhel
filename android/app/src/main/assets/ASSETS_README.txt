# Assets directory — model files go here
#
# REQUIRED before building (Phase B outputs):
#   yolov8n_pose.tflite       — pose extraction model (from notebook 01)
#   nyaaykhel_classifier.tflite — GRU event classifier (from notebook 03)
#   model_config.json          — window size, feature dim, class names (from notebook 03)
#
# Copy from your Google Drive after running the Colab notebooks:
#   model/yolov8n_pose.tflite
#   model/nyaaykhel_classifier.tflite
#   model/model_config.json
#
# The app will fail to start if any of these are missing.
