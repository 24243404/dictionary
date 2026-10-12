# Dictionary

A small dictionary application made with Java, HTML, and CSS. You can search
for words, add definitions, and remove entries. New entries are saved on your
computer.

## Run the application

1. Open a terminal in this folder.
2. Compile the Java program:

   ```text
   javac dictionary.java
   ```

3. Start the web application:

   ```text
   java dictionary
   ```

4. Open [http://127.0.0.1:8080](http://127.0.0.1:8080) in your browser.
5. Press `Ctrl+C` in the terminal to stop the application.

The app uses port `8080` by default. To choose a different port, pass it when
starting the program, for example `java dictionary 9000`.

To use the text-based version instead, run `java dictionary --console`.
