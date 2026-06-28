**This is vibe code, created by Claude AI and optimized by Google Gemini.**

My local utility uses the [Dropcountr platform](https://dropcountr.com) to connect to their smart water meter so users can track their water usage using the mobile Dropcountr app. 

Dropcountr does not have an official public API. I wanted to import Dropcountr data into Hubitat to create actionable events based on water usage. So I used several iterative cycles of Claude AI to create a Hubitat driver for a reverse-engineered Dropcountr API. I then used Google Gemini to optimize the code created by Claude AI.
A virtual device created using this driver will pull in data from Dropcountr at the specified interval (15 mins to 4 hours). The virtual device will have the following states:

<img width="473" height="278" alt="image" src="https://github.com/user-attachments/assets/d4cbd0c9-c956-462a-b9ef-aeea49bf16fb" />

**To install:**
1. Create a new Hubitat driver and paste the code into it. Save the driver.
2. Create a new virtual device and assign the "Dropcountr Water Monitor" driver to it.
3. Open the device, go into Preferences and enter your Dropcountr app username/password. You can also set the poll interval.
4. Click the "Initialize" button on the device page.

If all goes well, you should start pulling in data.
