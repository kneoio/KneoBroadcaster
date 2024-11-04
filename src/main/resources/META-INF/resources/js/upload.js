document.addEventListener('DOMContentLoaded', function() {
    const fileInput = document.getElementById('fileInput');
    const uploadButton = document.getElementById('uploadButton');
    const fileName = document.getElementById('fileName');
    const status = document.getElementById('status');
    const progressContainer = document.getElementById('progressContainer');
    const uploadProgress = document.getElementById('uploadProgress');

    fileInput.addEventListener('change', function() {
        if (this.files.length > 0) {
            fileName.textContent = this.files[0].name;
            uploadButton.disabled = false;
        } else {
            fileName.textContent = 'No file selected';
            uploadButton.disabled = true;
        }
    });
});

function uploadFile() {
    const fileInput = document.getElementById('fileInput');
    const uploadButton = document.getElementById('uploadButton');
    const fileName = document.getElementById('fileName');
    const status = document.getElementById('status');
    const progressContainer = document.getElementById('progressContainer');
    const uploadProgress = document.getElementById('uploadProgress');

    const file = fileInput.files[0];
    if (!file) {
        status.textContent = 'Please select a file first';
        return;
    }

    const formData = new FormData();
    formData.append('file', file);
    formData.append('name', file.name);

    uploadButton.disabled = true;
    progressContainer.style.display = 'block';
    status.textContent = 'Uploading...';

    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/fragments/upload', true);

    xhr.upload.onprogress = function(e) {
        if (e.lengthComputable) {
            const percentComplete = (e.loaded / e.total) * 100;
            uploadProgress.value = percentComplete;
        }
    };

    xhr.onload = function() {
        try {
            if (xhr.status === 200) {
                status.textContent = 'Upload successful!';
                fileInput.value = '';
                fileName.textContent = 'No file selected';
                uploadButton.disabled = true;
            } else {
                status.textContent = `Upload failed: ${xhr.statusText}`;
                uploadButton.disabled = false;
            }
        } catch (e) {
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