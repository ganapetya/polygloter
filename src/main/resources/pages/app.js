let currentSessionId = null;

// Start session when page loads
window.onload = function() {
    startSession();
    // Add event listener for translate button
    document.getElementById('translateBtn').addEventListener('click', translate);
    // Add event listener for listen button
    document.getElementById('listenBtn').addEventListener('click', listen);
    // Add event listener for source language changes
    document.getElementById('sourceLanguage').addEventListener('change', updateTargetLanguages);
    // Initialize target languages state
    updateTargetLanguages();
    // Initialize analysis functionality
    initializeAnalysis();
};

async function startSession() {
    try {
        const response = await fetch('/api/session/start', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({})
        });
        
        const data = await response.json();
        if (data.success) {
            currentSessionId = data.sessionId;
            document.getElementById('sessionId').textContent = data.sessionId.substring(0, 8) + '...';
        }
    } catch (error) {
        console.error('Failed to start session:', error);
    }
}

// Text validation and sanitization functions
function validateAndSanitizeText(text) {
    // Remove null bytes and other control characters that might cause issues
    // but preserve legitimate whitespace like spaces, tabs, and line breaks
    let sanitized = text.replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g, '');
    
    // Check for suspicious patterns
    const validation = {
        isValid: true,
        warnings: [],
        sanitizedText: sanitized
    };
    
    // Check text length (reasonable limits)
    if (text.length > 5000) {
        validation.warnings.push('Text is very long (over 5000 characters). This may cause slower processing.');
    }
    
    // Check for excessive line breaks (more than 20 consecutive newlines)
    if (/\n{20,}/.test(text)) {
        validation.warnings.push('Text contains excessive line breaks which have been normalized.');
        validation.sanitizedText = sanitized.replace(/\n{3,}/g, '\n\n');
    }
    
    // Check for unusual character combinations that might indicate encoding issues
    if (/[\uFFFD\uFEFF]/.test(text)) {
        validation.warnings.push('Text contains invalid Unicode characters which have been removed.');
        validation.sanitizedText = validation.sanitizedText.replace(/[\uFFFD\uFEFF]/g, '');
    }
    
    // Check if text is just whitespace after sanitization
    if (validation.sanitizedText.trim().length === 0) {
        validation.isValid = false;
        validation.warnings.push('Text contains only whitespace or invalid characters.');
    }
    
    return validation;
}

function showTextValidationWarnings(warnings) {
    if (warnings.length > 0) {
        const warningMsg = 'Text validation warnings:\n' + warnings.join('\n') + '\n\nContinue with translation?';
        return confirm(warningMsg);
    }
    return true;
}

async function translate() {
    const inputText = document.getElementById('inputText').value.trim();
    if (!inputText) {
        alert('Please enter some text to translate');
        return;
    }
    
    // Validate and sanitize input text
    const textValidation = validateAndSanitizeText(inputText);
    if (!textValidation.isValid) {
        alert('Invalid text input: ' + textValidation.warnings.join(' '));
        return;
    }
    
    // Show warnings and get user confirmation if needed
    if (textValidation.warnings.length > 0) {
        if (!showTextValidationWarnings(textValidation.warnings)) {
            return; // User cancelled
        }
    }
    
    // Use sanitized text for translation
    const cleanText = textValidation.sanitizedText;
    
    const sourceLanguage = document.getElementById('sourceLanguage').value;
    const targetLanguages = [];
    if (document.getElementById('langRu').checked) targetLanguages.push('ru');
    if (document.getElementById('langEn').checked) targetLanguages.push('en');
    if (document.getElementById('langDe').checked) targetLanguages.push('de');
    if (document.getElementById('langNo').checked) targetLanguages.push('no');
    if (document.getElementById('langHe').checked) targetLanguages.push('he');
    if (document.getElementById('langUk').checked) targetLanguages.push('uk');
    
    if (targetLanguages.length === 0) {
        alert('Please select at least one target language');
        return;
    }
    
    if (!currentSessionId) {
        alert('Session not started. Please refresh the page.');
        return;
    }
    
    const translateBtn = document.getElementById('translateBtn');
    const translationsDiv = document.getElementById('translations');
    
    translateBtn.disabled = true;
    translateBtn.textContent = '🔄 Translating...';
    
    translationsDiv.innerHTML = '<div class="loading">Translating...</div>';
    
    console.log('Starting translation for:', cleanText, 'from', sourceLanguage, 'to languages:', targetLanguages);
    
    try {
        const response = await fetch('/api/translate', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                text: cleanText,
                sourceLanguage: sourceLanguage,
                targetLanguages: targetLanguages,
                sessionId: currentSessionId
            })
        });
        
        const data = await response.json();
        console.log('Translation response:', data);
        
        if (data.error) {
            translationsDiv.innerHTML = '<div class="error">Error: ' + data.error + '</div>';
        } else if (data.requestId) {
            console.log('Starting polling for requestId:', data.requestId);
            pollForResults(data.requestId, translationsDiv);
        } else {
            translationsDiv.innerHTML = '<div class="error">Unexpected response format</div>';
        }
    } catch (error) {
        console.error('Translation error:', error);
        translationsDiv.innerHTML = '<div class="error">Network error: ' + error.message + '</div>';
    } finally {
        translateBtn.disabled = false;
        translateBtn.textContent = '🚀 Translate';
    }
}

async function pollForResults(requestId, translationsDiv) {
    let attempts = 0;
    const maxAttempts = 30;
    
    const poll = async () => {
        try {
            const response = await fetch('/api/translate/result/' + requestId);
            const data = await response.json();
            
            console.log('Polling response:', data);
            
            if (data.success !== undefined) {
                if (data.success) {
                    const translationsHtml = data.translations.map(translation => {
                        const [lang, text] = translation;
                        return '<div class="translation-item">' +
                               '<div class="translation-lang">' + lang.toUpperCase() + '</div>' +
                               '<div class="translation-text">' + text + '</div>' +
                               '</div>';
                    }).join('');
                    
                    translationsDiv.innerHTML = translationsHtml;
                } else {
                    translationsDiv.innerHTML = '<div class="error">Translation failed: ' + (data.error || 'Unknown error') + '</div>';
                }
                return;
            } else if (data.status === 'not_found' && attempts < maxAttempts) {
                attempts++;
                setTimeout(poll, 1000);
            } else {
                translationsDiv.innerHTML = '<div class="error">Translation timeout or error</div>';
            }
        } catch (error) {
            console.error('Polling error:', error);
            if (attempts < maxAttempts) {
                attempts++;
                setTimeout(poll, 1000);
            } else {
                translationsDiv.innerHTML = '<div class="error">Polling error: ' + error.message + '</div>';
            }
        }
    };
    
    poll();
}

function updateTargetLanguages() {
    const sourceLanguage = document.getElementById('sourceLanguage').value;
    const targetCheckboxes = {
        'ru': document.getElementById('langRu'),
        'en': document.getElementById('langEn'),
        'de': document.getElementById('langDe'),
        'no': document.getElementById('langNo'),
        'he': document.getElementById('langHe'),
        'uk': document.getElementById('langUk')
    };
    
    // Reset all checkboxes and enable them
    Object.values(targetCheckboxes).forEach(checkbox => {
        const group = checkbox.closest('.checkbox-group');
        group.classList.remove('disabled');
        checkbox.disabled = false;
    });
    
    // Disable the selected source language
    if (targetCheckboxes[sourceLanguage]) {
        const group = targetCheckboxes[sourceLanguage].closest('.checkbox-group');
        group.classList.add('disabled');
        targetCheckboxes[sourceLanguage].disabled = true;
        targetCheckboxes[sourceLanguage].checked = false;
    }
}

async function listen() {
    const inputText = document.getElementById('inputText').value.trim();
    if (!inputText) {
        alert('Please enter some text to listen to');
        return;
    }
    
    // Validate and sanitize input text
    const textValidation = validateAndSanitizeText(inputText);
    if (!textValidation.isValid) {
        alert('Invalid text input: ' + textValidation.warnings.join(' '));
        return;
    }
    
    // Show warnings and get user confirmation if needed
    if (textValidation.warnings.length > 0) {
        if (!showTextValidationWarnings(textValidation.warnings)) {
            return; // User cancelled
        }
    }
    
    // Use sanitized text for TTS
    const cleanText = textValidation.sanitizedText;
    
    const sourceLanguage = document.getElementById('sourceLanguage').value;
    const speakingRate = parseFloat(document.getElementById('speakingRate').value);
    const listenBtn = document.getElementById('listenBtn');
    
    listenBtn.disabled = true;
    listenBtn.textContent = '🔄 Processing...';
    
    console.log('Starting TTS for:', cleanText, 'in language:', sourceLanguage, 'at rate:', speakingRate);
    
    try {
        const response = await fetch('/api/tts', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                text: cleanText,
                language: sourceLanguage,
                speakingRate: speakingRate,
                sessionId: currentSessionId
            })
        });
        
        const data = await response.json();
        console.log('TTS response:', data);
        
        if (data.error) {
            alert('TTS Error: ' + data.error);
        } else if (data.requestId) {
            console.log('Starting polling for TTS requestId:', data.requestId);
            pollForTTSResults(data.requestId);
        } else {
            alert('Unexpected TTS response format');
        }
    } catch (error) {
        console.error('TTS error:', error);
        alert('Network error: ' + error.message);
    } finally {
        listenBtn.disabled = false;
        listenBtn.textContent = '🔊 Listen';
    }
}

async function pollForTTSResults(requestId) {
    let attempts = 0;
    const maxAttempts = 30;
    
    const poll = async () => {
        try {
            const response = await fetch('/api/tts/result/' + requestId);
            const data = await response.json();
            
            console.log('TTS polling response:', data);
            
            if (data.success !== undefined) {
                if (data.success && data.audioUrl) {
                    // Stop any currently playing audio
                    if (window.currentAudio) {
                        window.currentAudio.pause();
                        window.currentAudio.currentTime = 0;
                        window.currentAudio = null;
                    }
                    
                    // Create new audio object and play
                    console.log('Playing audio with URL:', data.audioUrl.substring(0, 50) + '...');
                    const audio = new Audio(data.audioUrl);
                    window.currentAudio = audio;
                    
                    // Force no caching
                    audio.preload = 'none';
                    audio.load();
                    
                    audio.play().catch(error => {
                        console.error('Error playing audio:', error);
                        alert('Error playing audio: ' + error.message);
                    });
                } else {
                    alert('TTS failed: ' + (data.error || 'Unknown error'));
                }
                return;
            } else if (data.status === 'not_found' && attempts < maxAttempts) {
                attempts++;
                setTimeout(poll, 1000);
            } else {
                alert('TTS timeout or error');
            }
        } catch (error) {
            console.error('TTS polling error:', error);
            if (attempts < maxAttempts) {
                attempts++;
                setTimeout(poll, 1000);
            } else {
                alert('TTS polling error: ' + error.message);
            }
        }
    };
    
    poll();
}

// Analysis functionality
let selectedText = '';
let selectedContext = '';

function initializeAnalysis() {
    const inputTextarea = document.getElementById('inputText');
    const analysisPanel = document.getElementById('analysisPanel');
    const analyzeBtn = document.getElementById('analyzeBtn');
    const analysisWindow = document.getElementById('analysisWindow');
    const closeAnalysisBtn = document.getElementById('closeAnalysisBtn');
    
    // Handle text selection
    inputTextarea.addEventListener('mouseup', handleTextSelection);
    inputTextarea.addEventListener('keyup', handleTextSelection);
    
    // Handle analyze button click
    analyzeBtn.addEventListener('click', performAnalysis);
    
    // Handle close analysis window
    closeAnalysisBtn.addEventListener('click', closeAnalysisWindow);
    
    // Hide analysis panel when clicking elsewhere
    document.addEventListener('click', function(event) {
        if (!analysisPanel.contains(event.target) && !inputTextarea.contains(event.target)) {
            hideAnalysisPanel();
        }
    });
    
    // Fetch and display current model name
    fetchModelName();
}

async function fetchModelName() {
    try {
        const response = await fetch('/api/analysis/model');
        const data = await response.json();
        if (data.model) {
            document.getElementById('modelName').textContent = data.model;
        }
    } catch (error) {
        console.log('Could not fetch model name:', error);
        // Keep default fallback text
    }
}

function handleTextSelection(event) {
    const inputTextarea = document.getElementById('inputText');
    const analysisPanel = document.getElementById('analysisPanel');
    
    const selection = window.getSelection();
    const textSelected = selection.toString().trim();
    
    if (textSelected && textSelected.length > 0) {
        selectedText = textSelected;
        selectedContext = inputTextarea.value;
        
        // Position the analysis panel near the selection
        const rect = inputTextarea.getBoundingClientRect();
        const textareaRect = {
            top: rect.top + window.scrollY,
            left: rect.left + window.scrollX,
            width: rect.width,
            height: rect.height
        };
        
        // Position panel in the center-right area of the textarea
        analysisPanel.style.top = (textareaRect.top + textareaRect.height / 2 - 25) + 'px';
        analysisPanel.style.left = (textareaRect.left + textareaRect.width - 60) + 'px';
        
        showAnalysisPanel();
    } else {
        hideAnalysisPanel();
    }
}

function showAnalysisPanel() {
    const analysisPanel = document.getElementById('analysisPanel');
    analysisPanel.style.display = 'block';
}

function hideAnalysisPanel() {
    const analysisPanel = document.getElementById('analysisPanel');
    analysisPanel.style.display = 'none';
}

async function performAnalysis() {
    if (!selectedText) {
        alert('Please select some text to analyze');
        return;
    }
    
    if (!currentSessionId) {
        alert('Session not started. Please refresh the page.');
        return;
    }
    
    // Validate and sanitize selected text
    const selectedTextValidation = validateAndSanitizeText(selectedText);
    if (!selectedTextValidation.isValid) {
        alert('Invalid selected text: ' + selectedTextValidation.warnings.join(' '));
        return;
    }
    
    // Validate and sanitize context text
    const contextValidation = validateAndSanitizeText(selectedContext);
    if (!contextValidation.isValid) {
        alert('Invalid context text: ' + contextValidation.warnings.join(' '));
        return;
    }
    
    // Show warnings for selected text if any
    if (selectedTextValidation.warnings.length > 0) {
        if (!showTextValidationWarnings(selectedTextValidation.warnings)) {
            return; // User cancelled
        }
    }
    
    // Use sanitized text for analysis
    const cleanSelectedText = selectedTextValidation.sanitizedText;
    const cleanContext = contextValidation.sanitizedText;
    
    const sourceLanguage = document.getElementById('sourceLanguage').value;
    const defaultLanguage = document.getElementById('defaultLanguage').value;
    
    hideAnalysisPanel();
    showAnalysisWindow();
    
    const analysisContent = document.getElementById('analysisContent');
    analysisContent.innerHTML = '<div class="loading">Analyzing text...</div>';
    
    console.log('Starting analysis for:', cleanSelectedText, 'in context of:', cleanContext.substring(0, 50) + '...');
    
    try {
        const response = await fetch('/api/analyze', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                contextText: cleanContext,
                textToAnalyze: cleanSelectedText,
                inputLanguage: sourceLanguage,
                outputLanguage: defaultLanguage,
                sessionId: currentSessionId
            })
        });
        
        const data = await response.json();
        console.log('Analysis response:', data);
        
        if (data.error) {
            analysisContent.innerHTML = '<div class="error">Error: ' + data.error + '</div>';
        } else if (data.requestId) {
            console.log('Starting polling for analysis requestId:', data.requestId);
            pollForAnalysisResults(data.requestId, analysisContent);
        } else {
            analysisContent.innerHTML = '<div class="error">Unexpected response format</div>';
        }
    } catch (error) {
        console.error('Analysis error:', error);
        analysisContent.innerHTML = '<div class="error">Network error: ' + error.message + '</div>';
    }
}

async function pollForAnalysisResults(requestId, analysisContent) {
    let attempts = 0;
    const maxAttempts = 30;
    
    const poll = async () => {
        try {
            const response = await fetch('/api/analyze/result/' + requestId);
            const data = await response.json();
            
            console.log('Analysis polling response:', data);
            
            if (data.success !== undefined) {
                if (data.success) {
                    analysisContent.innerHTML = data.analysisHtml;
                } else {
                    analysisContent.innerHTML = '<div class="error">Analysis failed: ' + (data.error || 'Unknown error') + '</div>';
                }
                return;
            } else if (data.status === 'not_found' && attempts < maxAttempts) {
                attempts++;
                setTimeout(poll, 1000);
            } else {
                analysisContent.innerHTML = '<div class="error">Analysis timeout or error</div>';
            }
        } catch (error) {
            console.error('Analysis polling error:', error);
            if (attempts < maxAttempts) {
                attempts++;
                setTimeout(poll, 1000);
            } else {
                analysisContent.innerHTML = '<div class="error">Polling error: ' + error.message + '</div>';
            }
        }
    };
    
    poll();
}

function showAnalysisWindow() {
    const analysisWindow = document.getElementById('analysisWindow');
    analysisWindow.style.display = 'block';
}

function closeAnalysisWindow() {
    const analysisWindow = document.getElementById('analysisWindow');
    analysisWindow.style.display = 'none';
} 