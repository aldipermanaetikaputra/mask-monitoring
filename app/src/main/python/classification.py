import io
import numpy as np
from tflite_runtime.interpreter import Interpreter
from PIL import Image
from utils.anchor_generator import generate_anchors
from utils.anchor_decode import decode_bbox
from utils.nms import single_class_non_max_suppression
from os.path import dirname, join

# Load TFLite model and allocate tensors.
interpreter = Interpreter(model_path=join(dirname(__file__), "model.tflite"))

# Get input and output tensors.
input_details = interpreter.get_input_details()
output_details = interpreter.get_output_details()

interpreter.allocate_tensors()

# anchor configuration
feature_map_sizes = [[33, 33], [17, 17], [9, 9], [5, 5], [3, 3]]
anchor_sizes = [[0.04, 0.056], [0.08, 0.11], [0.16, 0.22], [0.32, 0.45], [0.64, 0.72]]
anchor_ratios = [[1, 0.62, 0.42]] * 5

# generate anchors
anchors = generate_anchors(feature_map_sizes, anchor_sizes, anchor_ratios)

# for inference , the batch size is 1, the model output shape is [1, N, 4],
# so we expand dim for anchors to [1, anchor_num, 4]
anchors_exp = np.expand_dims(anchors, axis=0)

def inference(image,
              conf_thresh=0.5,
              iou_thresh=0.4,
              ):
    '''
    Main function of detection inference
    :param image: 3D numpy array of image
    :param conf_thresh: the min threshold of classification probabity.
    :param iou_thresh: the IOU threshold of NMS
    :param target_shape: the model input size.
    :param draw_result: whether to daw bounding box to the image.
    :param show_result: whether to display the image.
    :return:
    '''
    # image = np.copy(image)
    output_info = []
    image_np = np.divide(image, 255, dtype=np.float32)  # 归一化到0~1
    # print(image_np)
    image_exp = np.expand_dims(image_np, axis=0)

    # input_details[0]['index'] = the index which accepts the input
    interpreter.set_tensor(input_details[0]['index'], image_exp)

    # run the inference
    interpreter.invoke()

    # output_details[0]['index'] = the index which provides the input
    y_cls_output = interpreter.get_tensor(output_details[0]['index'])
    y_bboxes_output = interpreter.get_tensor(output_details[1]['index'])

    # remove the batch dimension, for batch is always 1 for inference.
    y_bboxes = decode_bbox(anchors_exp, y_bboxes_output)[0]
    y_cls = y_cls_output[0]
    # To speed up, do single class NMS, not multiple classes NMS.
    bbox_max_scores = np.max(y_cls, axis=1)
    bbox_max_score_classes = np.argmax(y_cls, axis=1)

    # keep_idx is the alive bounding box after nms.
    keep_idxs = single_class_non_max_suppression(y_bboxes,
                                                 bbox_max_scores,
                                                 conf_thresh=conf_thresh,
                                                 iou_thresh=iou_thresh,
                                                 )

    for idx in keep_idxs:
        conf = float(bbox_max_scores[idx])
        class_id = bbox_max_score_classes[idx]

        output_info.append([class_id, conf])

    return output_info

def predict(input):
    img = io.BytesIO(bytes(input))
    img = Image.open(img)
    img = img.resize((260,260), Image.ANTIALIAS)
    img = np.array(img, dtype = np.uint8)

    result = inference(img)
    return result
