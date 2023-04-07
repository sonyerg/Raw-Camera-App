import 'dart:developer';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';

void main() => runApp(const MyApp());

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: MyHomePage(),
    );
  }
}

class MyHomePage extends StatefulWidget {
  const MyHomePage({super.key});

  @override
  State<MyHomePage> createState() => _MyHomePageState();
}

class _MyHomePageState extends State<MyHomePage> {
  static const platform =
      MethodChannel('com.example.raw_image_camera_app/camera2');
  List<int> _cameraIdList = [];

  File? image;

  @override
  void initState() {
    super.initState();
    getCameraIdList();
  }

  Future<void> getCameraIdList() async {
    try {
      List<dynamic> cameraIdList =
          await platform.invokeMethod('getCameraIdList');

      setState(() {
        _cameraIdList = cameraIdList.map((e) => int.parse(e)).toList();
      });
    } on PlatformException catch (e) {
      print("Failed to get camera ID list: '${e.message}'.");
    }
  }

  Future<File> captureImage(String cameraId) async {
    try {
      final String? imagePath =
          await platform.invokeMethod('captureImage', {'cameraId': cameraId});

      if (imagePath != null) {
        File imageFile = File(imagePath);
        return imageFile;
      } else {
        print('Image capture failed');
        throw Exception("Failed to capture image");
      }
    } on PlatformException catch (e) {
      print("Failed to capture image: '${e.message}'.");
      throw Exception("Failed to capture image");
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SingleChildScrollView(
        child: Column(
          children: [
            ListView.builder(
              physics: const NeverScrollableScrollPhysics(),
              shrinkWrap: true,
              itemCount: _cameraIdList.length,
              itemBuilder: (context, index) {
                return ListTile(
                  title: Text("Camera ID: ${_cameraIdList[index]}"),
                );
              },
            ),
            if (image != null) Image.file(image!),
            if (image != null) Text('Format : ${image!.path}'),
            ElevatedButton(
                onPressed: () async {
                  log('asdfasd');
                  final res = await captureImage('0');
                  setState(() {
                    image = res;
                  });
                },
                child: Text('capture')),
          ],
        ),
      ),
    );
  }
}
