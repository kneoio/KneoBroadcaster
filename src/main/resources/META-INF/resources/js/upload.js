document.addEventListener('DOMContentLoaded', function() {
    const fileInput = document.getElementById('fileInput');
    const uploadButton = document.getElementById('uploadButton');
    const fileName = document.getElementById('fileName');
    const autoGenerateIntro = document.getElementById('autoGenerateIntro');
    const introText = document.getElementById('introText');

    fileInput.addEventListener('change', function() {
        if (this.files.length > 0) {
            fileName.textContent = this.files[0].name;
            uploadButton.disabled = false;
        } else {
            fileName.textContent = 'No file selected';
            uploadButton.disabled = true;
        }
    });

    autoGenerateIntro.addEventListener('change', function() {
        introText.disabled = this.checked;
        if (this.checked) {
            introText.value = '';
        }
    });
});

function uploadFile() {
    const fileInput = document.getElementById('fileInput');
    const uploadButton = document.getElementById('uploadButton');
    const status = document.getElementById('status');
    const progressContainer = document.getElementById('progressContainer');
    const uploadProgress = document.getElementById('uploadProgress');
    const autoGenerateIntro = document.getElementById('autoGenerateIntro');
    const introText = document.getElementById('introText');
    const playImmediately = document.getElementById('playImmediately');

    const file = fileInput.files[0];
    if (!file) {
        status.textContent = 'Please select a file first';
        return;
    }

    const formData = new FormData();
    formData.append('file', file);

    const uploadData = {
        autoGenerateIntro: autoGenerateIntro.checked,
        introductionText: introText.value,
        playImmediately: playImmediately.checked
    };
    formData.append('data', JSON.stringify(uploadData));

    uploadButton.disabled = true;
    progressContainer.style.display = 'block';
    status.textContent = 'Uploading...';

    const xhr = new XMLHttpRequest();
    xhr.open('POST', 'http://localhost:38707/api/kneo/soundfragments/upload-with-intro', true);

    xhr.upload.onprogress = function(e) {
        if (e.lengthComputable) {
            const percentComplete = (e.loaded / e.total) * 100;
            uploadProgress.value = percentComplete;
        }
    };

    xhr.onload = function() {
        if (xhr.status === 202) {
            status.textContent = 'Upload successful!';
            fileInput.value = '';
            fileName.textContent = 'No file selected';
            introText.value = '';
            autoGenerateIntro.checked = false;
            playImmediately.checked = false;
            uploadButton.disabled = true;
        } else {
            status.textContent = `Upload failed: ${xhr.statusText}`;
            uploadButton.disabled = false;
        }
        progressContainer.style.display = 'none';
    };

    xhr.onerror = function() {
        status.textContent = 'Upload failed! Network error.';
        progressContainer.style.display = 'none';
        uploadButton.disabled = false;
    };

    xhr.send(formData);
}