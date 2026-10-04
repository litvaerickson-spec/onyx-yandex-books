
  var input = document.querySelector("[data-testid=\"text-field-input\"]") || document.querySelector("input[type=\"tel\"]");
  if (input) {
    var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
    if (nativeSetter) {
      nativeSetter.call(input, "+79991112233");
    } else {
      input.value = "+79991112233";
    }
    input.dispatchEvent(new Event("input", { bubbles: true }));
    input.dispatchEvent(new Event("change", { bubbles: true }));
    console.log("Input value set to: " + input.value);
    var btn = document.querySelector("[data-testid=\"phone-next\"]");
    console.log("Next button found: " + !!btn);
  } else {
    console.log("Input not found");
  }
