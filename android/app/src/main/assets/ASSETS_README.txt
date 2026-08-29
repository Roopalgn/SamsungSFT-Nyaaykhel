# Assets directory — model files go here
#
# REQUIRED before building (Phase B outputs):
#   yolov8n_pose.tflite       — pose extraction model (from notebook 01)
#   nyaaykhel_classifier.tflite — legacy 4-class GRU event classifier (not recommended path)
#   touch_candidate_classifier.tflite — active-learning binary touch classifier
#   model_config.json          — model metadata/config
#
# Copy from your Google Drive after running the Colab notebooks:
#   model/yolov8n_pose.tflite
#   model/nyaaykhel_classifier.tflite
#   data/processed/touch_active_learning/model/touch_candidate_classifier.tflite
#   model/model_config.json
#
# The current app path still expects nyaaykhel_classifier.tflite until the
# active-learning runtime feature extractor is wired into MainViewModel.
